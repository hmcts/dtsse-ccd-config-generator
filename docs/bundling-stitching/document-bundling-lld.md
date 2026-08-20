# Document Bundling SDK: Low-Level Design

**Status:** As-built. Describes `sdk/document-bundling` as implemented and verified (414 module
tests, 60-test e2e suite, characterisation parity suite enabled).

**Companions:** [the module design](document-bundling-module-design.md) (rationale and decisions),
[consumer usage](document-bundling-consumer-usage.md) (integration walkthrough),
[for-devs reference](document-bundling-for-devs.md) (API/gotchas quick reference).

This document explains how each feature works, package by package. Class names
are exact; behavioural claims are pinned by tests in the corresponding test package.

---

## 1. Module layout

One published artifact, `com.github.hmcts:document-bundling`, in the `sdk` composite build.
Package boundaries (all under `uk.gov.hmcts.ccd.sdk.bundling`):

| Package | Role | Key types |
|---|---|---|
| `api` | The stable public contract: models, ports, SPI, errors | `BundleRenderer`, `BundleRequest`, `CcdBundle`, `HandlerRegistry`, `BundleErrorCode` |
| `render` | Pipeline orchestration | `DefaultBundleRenderer`, `Resolution`, `MediaTypes`, `JobDirectory` |
| `convert` | Built-in per-media-type handlers | `PdfPassthroughHandler`, `ImageHandler`, `MediaLinkHandler`, `DocmosisOfficeHandler` |
| `pdf` | PDF assembly (ported from em-stitching-api) | `PdfBundleAssembler`, `TocRenderer`, `OutlineBuilder`, `WatermarkRenderer`, `GeneratedPages` |
| `docmosis` | Client for the shared Docmosis render service | `HttpDocmosisRenderService`, `DocmosisConnection` |
| `cdam` | CDAM adapters (invariant artifact storage) | `CdamBundleDestination`, `CdamDocumentResolver`, `BundlingAuthenticationProvider` |
| `job` | Durable job runner (transactional outbox) | `OutboxBundleJobService`, `BundleJobWorker`, `BundleJobRepository` |
| `spring` | Opt-in auto-configuration | `BundlingAutoConfiguration`, `BundlingProperties` |
| `testing` | Consumer-usable test double | `FilesystemBundleDestination` |

Dependency direction: everything depends on `api`; `api` depends only on `docmosis` (the
`DocmosisRenderService` port surfaces in `HandlerContext`) and on `ccd-config-generator` for the
CCD types (`Document`, `ListValue`, `YesOrNo`) that `CcdBundle`/`StoredBundle` map onto. PDFBox
(3.0.8, matching em-stitching-api) is an implementation dependency and never appears in public
signatures.

## 2. Public API and request model (`api`)

`BundleRenderer` is the synchronous entry point: `render(BundleRequest, BundleExecutionContext)
→ BundleResult`, plus `handledMediaTypes()` (registry inspection) and `limits()` (effective
configuration, used by the job worker to sanity-check its lease against the render timeout).

`BundleRequest` is an explicit ordered tree — `BundleSection` (nested, each with an
`EmptySectionPolicy`) containing `BundleDocument`s (id, title, optional date, confidential flag,
opaque `DocumentReference`, optional `MediaPlaceholder`). Builders validate at construction
(non-blank fields, absolute media access URLs, ≤500-char notes, safe `.pdf`-suffixed output
filename, tree-wide unique document ids, at-least-one-document-or-placeholder); `RequestValidation`
re-runs the same invariants at render time because requests can arrive deserialised from the job
outbox, bypassing builders. All model classes carry Jackson builder annotations
(`@JsonDeserialize(builder=…)`/`@JsonPOJOBuilder`) so the outbox round-trips them losslessly;
`BundleExecutionContext` flattens its attribute map via `@JsonAnyGetter` and reserves the
`caseReference`/`initiator` keys to prevent identity hijack on round-trip.

The extension model is Jackson-style: `BundleRenderer.builder()` produces defaults equivalent to
the current microservice; `BundlingExtension.configure(BundlingExtensionContext)` mutates the
handler registry with fail-fast semantics — `addHandler` refuses a type that is already handled,
`replaceHandler` refuses one that is not, `removeHandler` reverts a type to unhandled — each error
naming the extension and media type. `HandlerRegistry` normalises media types (trim, lowercase,
parameters stripped) at registration and lookup; built-ins register first, extensions apply in
registration order, so the last registration wins and the effective registry is inspectable at
build time.

The **output format** is `CcdBundle` (+ `CcdBundleFolder`/`CcdBundleDocument`): a Lombok/Jackson
class kept JSON-compatible with em-ccd-orchestrator's `CcdBundleDTO` and with the bundle models
consumers already hold in `caseBundles` fields (both sides are
`@JsonIgnoreProperties(ignoreUnknown = true)`). It is a wire shape, not an importable CCD complex
type — definition stores reject the recursive folder type — so consumers persist its JSON into
their own bounded model. `BundleResult` carries the `CcdBundle` (`output`), the storage facts
(`stored`: CDAM links, size, SHA-256, hash token), per-document `DocumentResult`s (detected media
type, source checksum, page count, 1-based start page), `BundleWarning`s, and per-stage
`timings` — together the audit-grade generation report.

The **error catalogue** is the closed enum `BundleErrorCode` (`REQUEST_INVALID`,
`DOCUMENT_NOT_FOUND`, `DOCUMENT_ACCESS_DENIED`, `DOCUMENT_RESOLUTION_FAILED`,
`MEDIA_TYPE_UNSUPPORTED`, `DOCUMENT_CONTENT_INVALID`, `DOCMOSIS_NOT_CONFIGURED`,
`DOCUMENT_CONVERSION_FAILED`, `DOCUMENT_INSPECTION_FAILED`, `ASSEMBLY_FAILED`,
`OUTPUT_VALIDATION_FAILED`, `STORAGE_FAILED` (transient), `STORAGE_REJECTED` (permanent),
`LIMIT_EXCEEDED`, `TIMED_OUT`, `JOB_REQUEST_UNREADABLE`). `BundleGenerationException` composes a
message from code + stage + per-document `DocumentFailure`s + remediation hint, designed so one
App Insights line identifies what failed, on which document, at which stage, and what to do.
`BundleRenderTimeoutException` (subclass) adds a typed `timingsSoFar()` map including the
in-flight stage's elapsed portion. `BundleStorageException` carries a `permanent` flag that the
pipeline maps to `STORAGE_REJECTED` vs `STORAGE_FAILED`.

## 3. Rendering pipeline (`render`)

`DefaultBundleRenderer.render` executes the design's nine stages on the caller's thread, guarded
by a fair `Semaphore` (`maxConcurrentRenders`, default 2 — excess renders block) and a deadline
(`BundleLimits.maxElapsed`, default one minute end-to-end including permit wait). The deadline is
cooperative: checked between stages, before each reference, and between 64 KB spool chunks — a
single blocking I/O call can overshoot by its own duration, which the javadoc documents.

1. **VALIDATE** — `RequestValidation`: tree invariants, media placeholders must carry a media
   type that is registered *and* not a content type (PDF/image/office placeholders are rejected as
   `REQUEST_INVALID`), every document's media type must have a handler (`MEDIA_TYPE_UNSUPPORTED`
   names the type, the document, and the registered set; office types without Docmosis fail
   `DOCMOSIS_NOT_CONFIGURED` naming the `ccd.bundling.docmosis.*` properties), document-count
   limit.
2. **RESOLVE** — `Resolution`: references deduplicated, batched per provider, dispatched to the
   matching `DocumentResolver` (unknown provider fails fast naming the registered providers). Each
   `ResolvedDocument` is spooled to the job directory with SHA-256 computed during the copy and
   both declared and actual byte limits enforced mid-stream; every resolved stream is closed;
   media documents are never fetched.
3. **Fail-fast** — any unresolved reference aborts with one exception carrying every
   `DocumentFailure` (typed reasons map to `DOCUMENT_NOT_FOUND`/`DOCUMENT_ACCESS_DENIED`/
   `DOCUMENT_RESOLUTION_FAILED`), stage RESOLVE.
4. **Detection** — `MediaTypes`: exact offset-0 signatures first (PNG/JPEG/GIF/BMP/TIFF/RTF/
   MP3/ID3/MP4-ftyp/`%PDF-`), then ZIP/OLE2 container checks, then (only if nothing anchored
   matched) a windowed 1 KB `%PDF-` scan for spec-legal leading junk. Policy: exact detection wins
   for routing; declared wins for container-backed office types and unsignatured content;
   irreconcilable combinations (declared pdf/image over a container; confidently-detected
   audio/video on a *fetched* document) fail `DOCUMENT_CONTENT_INVALID` naming both types; a
   blank effective type fails `MEDIA_TYPE_UNSUPPORTED`.
5. **CONVERT** — registry dispatch via `DefaultHandlerContext` (temp files capped at 100 per
   document, the Docmosis service when configured, the effective limits, and the owning
   `BundleDocument` — the seam that lets media handlers build pages from metadata via
   `SyntheticMediaSource`, whose `content()` deliberately throws). Handler output is
   realpath-contained to the job directory; naked handler `RuntimeException`s are wrapped typed
   without copying their messages.
6. **INSPECT** — `PdfInspection`: loadable, unencrypted, page count > 0, sane dimensions,
   extractable-text presence recorded as a report fact; cumulative pages enforced against
   `maxTotalPages` before assembly.
7. **ASSEMBLE** — `AssemblyMapping` converts the request tree + handled documents into the pdf
   package's `AssemblyRequest` (sections→folders, `EmptySectionPage` placeholders per policy,
   presentation passthrough) and invokes `PdfBundleAssembler`; assembler warnings are aggregated.
8. **STORE** — internal consistency guard, output byte/page validation, then exactly one
   `BundleDestination.store` with a `FileArtifact` (fresh stream per `open()`, size, SHA-256,
   page count). A destination failure after possible upload reports "publication state unknown".
9. **Result + cleanup** — `CcdBundles` populates the output (stitched `Document` from
   `StoredBundle.toDocument()`, documents/folders echo with 1-based `sortIndex`/`ListValue` ids,
   presentation echoes mapped to wire values, `stitchStatus="DONE"`, `dateAndTime=now`,
   `id=externalId`); `JobDirectory` recursively deletes the per-job owner-only temp directory in a
   `finally` — success, failure, timeout, and `Error` alike.

Observability: SLF4J with MDC (`externalId`, `stage`, `documentId` — saved and restored so a
worker thread's own MDC survives), INFO stage transitions with counts, WARN per warning code,
exactly one ERROR at final failure. `RenderMetrics` publishes `ccd.bundling.stage` timers and
`ccd.bundling.{documents,pages,bytes,warnings,failures}` counters to an optional Micrometer
registry; tag cardinality is bounded (consumer-minted warning codes collapse to `extension`).

## 4. PDF assembly (`pdf`)

A decoupled port of em-stitching-api's rendering classes onto a sealed model:
`AssemblyRequest` → `AssemblyNode` (`AssemblyFolder` | `AssemblyItem`) → `AssemblyContent`
(`PdfSource` | `MediaLinkPage` | `EmptySectionPage`). `PdfBundleAssembler.assemble(request,
workDir)` returns `AssemblyResult` (output path, total pages, per-item 1-based start page and page
count in render order, warnings).

Components: `TocRenderer` (clickable contents with title/date/start-page columns, exact up-front
page estimation at em-stitching's 400 pt title wrap width — estimation is pinned exact at every
tested page boundary), `OutlineBuilder` (bookmarks mirroring the tree; source outlines rebuilt —
not COS-grafted — with styling, GoTo actions remapped, named destinations resolved to explicit
page destinations at copy time, depth capped at 100 with an `OUTLINE_TRUNCATED` warning, cycles
truncated), `WatermarkRenderer` (PDFBox `Overlay`, approved presets only, writes to a separate
file — never the one being read), `GeneratedPages` (deterministic PDFBox templates: title page,
empty-section page, media link page with clickable absolute URL), `PdfUtility` (text/link/
pagination drawing positioned against the **CropBox**, WinAnsi sanitisation with a
`TITLE_NOT_RENDERABLE` warning + `"Document <n>"` fallback when a title sanitises to nothing),
`PdfFonts` (per-assembly font instances — PDFBox `PDType1Font` mutates a plain `HashMap` on
encode, so no shared statics), `Checks`.

Memory model: one shared `ScratchFile` per assembly (64 MB in-heap budget spilling to `workDir`)
backs the merged document, every source load, and the watermarker; every `PDDocument` closes on
every path; watermark intermediates are tracked and deleted in the run's `finally`; the output
path is deleted at start and on failure so a stale bundle can never be republished.

Eleven deliberate divergences from em-stitching's rendered output are enumerated (with the field
each exempts) in `CharacterisationRegressionTest`'s policy javadoc and the design doc — including
four upstream production defects not replicated: outlines dropped unless `hasDocumentSubtitles`,
dangling named destinations, the off-page "Back to index" link (swapped coordinate arguments), and
the in-place watermark save that corrupts the evidence text layer.

## 5. Rendering parity: the characterisation baseline

`scripts/bundling-characterisation` is a standalone Gradle harness that compiles em-stitching-api's
real `PDFMerger`/`TableOfContents`/`PDFOutline`/`PDFWatermark` from a local checkout (pinned SHA in
its README) and renders 17 scenarios (TOC on/off, cover pages, document/folder coversheets, nested
folders, multiline titles, 102-page multi-page-TOC, both page-number formats, three pagination
positions, preserved/subtitled outlines, watermark, special characters, multiline×many-docs),
extracting semantic facts through the module's own `testsupport.PdfSemantics`: page count,
position-sorted whitespace-normalised per-page text, page-number stamps with coordinates
(region-restricted to the top/bottom 60 pt), per-page image XObjects hashed over decoded pixels,
outline tree with resolved targets, links with targets. Facts are byte-reproducible across runs
and committed under `sdk/document-bundling/src/test/resources/characterisation/`.

`CharacterisationRegressionTest` (enabled, 14 comparable scenarios) asserts the SDK's assembler
output semantically equals the goldens field-by-field, applying only the enumerated divergence
exemptions — so any rendering drift is either a declared divergence or a test failure.

## 6. Docmosis client (`docmosis`)

`HttpDocmosisRenderService` speaks the exact multipart contract em-stitching sends to
`/rs/convert` (`accessKey`, `outputName`, `file` with the original filename — Docmosis selects its
converter by extension) and `/rs/render` (`templateName`, `accessKey`, `outputName`, `data` JSON),
over JDK `HttpClient` pinned to HTTP/1.1 (no h2c upgrade headers). Hardening beyond the current
service: the source's real media type is sent (CRLF-stripped and reduced to a strict
`type/subtype` token — the value is attacker-influenced document-store metadata); responses stream
to owner-only temp files through a `BoundedFileSubscriber` with an output ceiling; the whole
exchange (headers *and* body) is bounded by the read timeout via `sendAsync` + cancellation; 2xx
responses must pass a `%PDF-` magic check or fail typed and non-transient; the source-size ceiling
is enforced before any bytes are sent; retries (bounded, default 1) apply to transient failures
only (connect/IO/5xx — never 4xx) with 200–400 ms jittered, interrupt-aware backoff.
`DocmosisConnection` validates endpoints/timeouts and redacts the access key from `toString()`;
no exception message ever carries the key or a response body.

## 7. CDAM adapters (`cdam`, `testing`)

**Resolver** (`CdamDocumentResolver`, provider `"cdam"`): exactly one authorised
`getDocumentBinary` per document — the redundant per-document metadata call of the current CDAM
path is deliberately dropped, halving the authorisation fan-out. Filename comes from the
`OriginalFileName` header, falling back to `Content-Disposition` parsing, sanitised
(separators/control chars/leading dots stripped, UUID fallback); media type is the raw
`Content-Type` string (never parsed with throwing APIs). Because the client's Feign decoder
buffers each binary as a `ByteArrayResource`, the resolver fetches **sequentially and spools each
binary to an owner-only temp file before fetching the next**, bounding peak heap to ~one document;
`close()` deletes the spool file. Failures are per-reference and typed: 404→`NOT_FOUND`,
401/403→`ACCESS_DENIED`, 408/429/5xx/transport/IO→`TRANSIENT_FAILURE`, other
4xx/malformed-header→`INVALID_CONTENT`; token acquisition happens once, before the loop, and its
failure is attributed to token acquisition, not CDAM. Details carry status codes and exception
class names only.

**Destination** (`CdamBundleDestination`): artifact storage is invariant — production bundles
always land in CDAM. Uploads via `CaseDocumentClientApi.uploadDocuments` with an explicit
`Classification` (`CdamUploadSettings` has no default — the current service's hardcoded PUBLIC is
deliberately not replicated). With `attachToCase=true` (the SDK's attachment adapter for
decentralised services), it validates `BundleExecutionContext.caseReference` as exactly 16 digits
**before** uploading, uploads, then calls `patchDocument` (CDAM `attachToCase`) with the upload's
hash token — requiring the service's S2S identity to hold CDAM's `ATTACH` permission. Attach
semantics are at-least-once: a crash between attach and the consumer's record can leave an
attached-but-unreferenced duplicate (documented; unattached uploads are TTL-disposed by CDAM).
Permanent rejections (4xx) surface as `STORAGE_REJECTED` and are never retried; transient failures
as `STORAGE_FAILED`. With `attachToCase=false` (default — correct for services whose documents
attach via case-data submission), the consumer must ensure the returned `document_hash` flows
through a CCD event so ccd-data-store performs the attach.

`testing.FilesystemBundleDestination` (main sourceset, tests/local runs only): owner-only
directory and file from creation, temp-file write + `ATOMIC_MOVE` rename so a failed overwrite
never destroys the previous bundle, path-segment-validated filenames.

## 8. Durable job runner (`job`)

A transactional outbox modelled on `sdk/task-management`. One table, `ccd_bundle_job`
(`V0001__ccd_bundle_job.sql` under `document-bundling-db/migration`): `external_id uuid` primary
key (the consumer-minted idempotency key), state, attempts, lease columns, `request`/
`selector_parameters`/`execution_context` JSONB, request/adapter versions, sanitised failure
columns, transient history, `result` JSONB (the `CcdBundle`), timestamps. No token, byte, or
signed-URL column exists by design.

`OutboxBundleJobService.submit` is a single `INSERT … ON CONFLICT DO NOTHING` in the caller's
active transaction — a bundle request exists exactly when the triggering CCD event commits;
duplicate external ids return the existing job (with a WARN). Under REPEATABLE READ a losing
concurrent duplicate gets the winner's job back via a bounded non-transactional re-read, but the
caller's transaction is already doomed by PostgreSQL — READ COMMITTED is the documented supported
isolation.

`BundleJobWorker` polls on a fixed delay, claims `min(batch, maxConcurrentRenders − inFlight)`
rows with `SELECT … FOR UPDATE SKIP LOCKED` + lease (claimable = due-and-QUEUED or
expired-lease-in-progress, **and** `attempts < maxAttempts`; a reaper terminally fails
exhausted stale rows), runs the configured `BundleDocumentSelector` (default: the request as
submitted; overriding it moves document-list compilation to execution time), renders, and writes
terminal state. Every terminal/requeue write is **lease-owner-guarded**
(`WHERE lease_owner = :me AND state IN (in-progress)`) with the update count checked — a worker
that lost its lease logs the loss and never overwrites the reclaimer, so renders may overlap after
lease expiry but exactly one publish is ever recorded. Rejected executor dispatches release the
claim (attempt handed back) and the in-flight counter is `finally`-guarded, so the worker cannot
wedge. Retry (`BundleJobRetryPolicy`): bounded exponential backoff for `DOCUMENT_RESOLUTION_FAILED`,
`DOCUMENT_CONVERSION_FAILED`, `STORAGE_FAILED`, `TIMED_OUT` only; everything else — including
`STORAGE_REJECTED`, validation, not-found, access-denied — fails terminally. Unreadable persisted
requests (corrupt JSON or a future `request_version`) fail `JOB_REQUEST_UNREADABLE` without
reaching the renderer. Progress events go to `BundleProgressListener`s with per-listener
exception isolation.

`BundleJobAutoConfiguration` registers all of this `@ConditionalOnMissingBean`, gated by the
umbrella `ccd.bundling.job.enabled` (all beans) and `ccd.bundling.job.worker.enabled` (worker
only). A nested Flyway configuration self-applies the module's migration through a module-owned
Flyway instance with its own history table (`ccd_bundle_job_flyway_history`),
`baselineOnMigrate(true)` + `baselineVersion("0")` so existing consumer schemas baseline instead
of failing while `V0001` still applies; opt-out via `ccd.bundling.job.auto-migrate=false`;
`flyway-core` is `compileOnly` and its absence backs off cleanly.

## 9. Spring auto-configuration (`spring`)

`BundlingAutoConfiguration` (`@AutoConfiguration(before = BundleJobAutoConfiguration.class,
afterName = "…CaseDocumentManagementClientAutoConfiguration")`, gated by `ccd.bundling.enabled`,
every bean `@ConditionalOnMissingBean`):

- **Docmosis**: `HttpDocmosisRenderService` when all three `ccd.bundling.docmosis.{convert-endpoint,
  render-endpoint, access-key}` are present; absent → office media types stay unhandled with the
  descriptive `DOCMOSIS_NOT_CONFIGURED` error at render time.
- **CDAM**: `CdamBundleDestination` + `CdamDocumentResolver` when the `ccd.bundling.cdam.*` triple
  is present *and* `CaseDocumentClientApi` + `BundlingAuthenticationProvider` beans exist —
  resolved via `ObjectProvider` at instantiation time plus a `BeanDefinitionRegistryPostProcessor`
  that prunes the CDAM definitions when collaborators are missing or a consumer destination exists,
  making the wiring immune to auto-configuration ordering (the registration-time
  `@ConditionalOnBean` trap). The auth provider is deliberately consumer-provided (token wiring is
  service-specific). Partial CDAM configuration produces a startup WARN naming exactly the missing
  keys/beans; fully-configured-but-missing-bean with no alternative wiring fails startup
  descriptively.
- **Renderer**: assembled from all `DocumentResolver` beans, the single `BundleDestination`, all
  `BundlingExtension` beans in `@Order` order (duplicate names WARN), optional
  `DocmosisRenderService`/`MeterRegistry`, per-field-merged limits, concurrency, and a
  startup-validated temp directory. `BundlingProperties` excludes the access key from `toString`.

## 10. Testing architecture

Per `docs/testing-strategy.md`: behaviour with real components, mocks only at external adapter
boundaries. The module's 414 tests comprise builder/model contract tests, the JSON wire-shape
pins, handler/registry semantics, pipeline end-to-end renders (real PDF layer + fixtures, fake
resolver, filesystem destination, stub Docmosis) covering every failure-semantics row with
attribution assertions, the enabled characterisation parity suite, Docmosis wire-contract tests
against a real local HTTP server, CDAM adapter tests (mocked Feign client — the external
boundary), Testcontainers-PostgreSQL job integration tests (claim contention, lease expiry,
double-publish guards, retry, Flyway on empty and pre-populated schemas), and the Spring
conditions matrix via `ApplicationContextRunner` (including unordered-auto-config simulations).
Every adversarial-review proof test from the seven review cycles is enabled; none is `@Disabled`.
The e2e project adds the full-stack proof: a decentralised event rendering through the real
embedded CCD/CDAM with hash-token attach asserted via `case_id` metadata, and a failure path
proving typed attribution with nothing published.
