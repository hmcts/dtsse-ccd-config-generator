# Document Bundling SDK: Developer Reference

**Audience:** service developers integrating `com.github.hmcts:document-bundling`.
**Companions:** [consumer usage](document-bundling-consumer-usage.md) (worked walkthrough),
[LLD](document-bundling-lld.md) (how it works inside), [design](document-bundling-module-design.md).

This is the quick reference: the API surface, what you can customise, what side effects the
module has in your service, and the things that bite.

---

## Minimum integration

```groovy
implementation 'com.github.hmcts:document-bundling:<version>'
```

Spring Boot: set properties, define two beans, done —

```yaml
ccd:
  bundling:
    docmosis:                                        # omit block = no office conversion
      convert-endpoint: ${DOCMOSIS_ENDPOINT}
      render-endpoint: ${DOCMOSIS_RENDER_ENDPOINT}
      access-key: ${DOCMOSIS_ACCESS_KEY}
    cdam:
      jurisdiction-id: MYJURISDICTION
      case-type-id: MyCaseType
      classification: RESTRICTED                     # explicit by design — never defaulted
      attach-to-case: true                           # see gotcha #1 before choosing
```

```java
@Bean BundlingAuthenticationProvider bundlingAuth(IdamClient idam, AuthTokenGenerator s2s) { ... }
@Component class MyResolver implements DocumentResolver { ... }   // provider "my-docs"
```

The auto-configuration assembles a `BundleRenderer` bean from every `DocumentResolver` bean, the
CDAM destination, Docmosis (when configured), and any `BundlingExtension` beans. Non-Spring/test
use: `BundleRenderer.builder().resolver(...).destination(...).build()`.

## API cheat sheet

| You call | You get |
|---|---|
| `BundleRequest.builder()` … `.root(BundleSection.builder("...").document(BundleDocument.builder()...))` | the ordered bundle tree; `externalId` (UUID you mint) is the idempotency key |
| `renderer.render(request, BundleExecutionContext.builder().caseReference("16digits").build())` | `BundleResult` — or a thrown `BundleGenerationException` |
| `result.output()` | `CcdBundle` — attach its JSON to your case (it's `CcdBundleDTO`-wire-compatible) |
| `result.stored()` | CDAM links + size + SHA-256 + hash token (the storage facts) |
| `result.documents()` / `warnings()` / `timings()` | the generation report — keep it for audit |
| `bundleJobService.submit(request, context)` | durable execution via the outbox (or submit `externalId` + selector parameters for execution-time document selection) |

Failures: catch `BundleGenerationException` → `code()` (stable enum, safe to alert on),
`stage()`, `documentFailures()` (each names the document + typed reason), `remediation()`. The
message alone is App-Insights-complete. Timeouts are `BundleRenderTimeoutException` with
`timingsSoFar()`. **Nothing is ever partially published**: any failure means no new bundle exists
(one nuance: a `STORAGE_FAILED` whose message says "publication state unknown").

## What you can customise — and what you can't

- **Per-media-type behaviour**: register `BundlingExtension` beans; `addHandler` for new types,
  `replaceHandler` to override a built-in (e.g. a branded media page), `removeHandler` to forbid a
  type. Wrong verb fails at startup naming your extension. Handlers get bounded services only
  (`HandlerContext`: temp files (≤100/document), the Docmosis client, limits, the owning
  `BundleDocument`) and their output must stay inside the job directory.
- **Built-in coverage**: PDF passthrough; PNG/JPEG(+`image/jpg`)/TIFF/BMP/GIF/SVG† images; the
  em-stitching office list via Docmosis; MP3/MP4 → generated link pages. Anything else needs an
  extension or the bundle fails naming the type.
- **Presentation**: `BundlePresentation.courtDefault()` + withers (TOC, section/document cover
  sheets, `PageNumbers` presets, `ConfidentialMarking`). Deliberately closed — no free coordinates,
  fonts, or overlays on evidence pages.
- **Limits**: `BundleLimits.defaults()` = 100 docs / 300 MB per source / 50 MB office source /
  1 GB output / 1,000 pages / 1 minute end-to-end. Override via `ccd.bundling.limits.*` per field
  or `.limits(...)`.
- **Concurrency**: `max-concurrent-renders` (default 2). Excess renders **block** on a fair
  permit; the wait counts toward the 1-minute deadline.
- **Not customisable**: where the PDF goes (always CDAM in production — the filesystem destination
  is for tests), the generated-page templates, and the presentation escape hatches the design
  deliberately withholds.

## Side effects in your service

- **Heap**: rendering runs in *your* JVM. Bounded by design: one 64 MB PDFBox scratch budget per
  render (spills to disk), one document's bytes at a time from CDAM, ~2× artifact size briefly
  during upload (client API limitation). Budget ≈ `maxConcurrentRenders × (64 MB + largest
  document)`.
- **Disk**: a per-render owner-only temp directory under `ccd.bundling.temp-directory` (default
  `java.io.tmpdir`) holding spooled sources, converted files, scratch spill, and the output —
  deleted on success, failure, timeout, and `Error`. Size ≈ sum of source bytes + output.
- **Threads**: `render()` runs on the caller's thread. The job worker adds one `@Scheduled` poll
  plus a dispatch executor sized to `max-concurrent-renders`.
- **Database** (only if you use the job runner): one table `ccd_bundle_job` plus its own Flyway
  history table `ccd_bundle_job_flyway_history`, self-migrated at startup (baseline-safe on
  existing schemas; opt out with `ccd.bundling.job.auto-migrate=false`; kill everything with
  `ccd.bundling.job.enabled=false`).
- **Network**: your resolver's fetches; one CDAM upload (+ one attach PATCH when enabled) per
  bundle; one Docmosis `/rs/convert` per office document. Total platform call volume matches the
  old stitching service.
- **Telemetry**: SLF4J through your logger (MDC: `externalId`, `stage`, `documentId`; one ERROR
  per failure), and `ccd.bundling.*` Micrometer timers/counters when a `MeterRegistry` bean
  exists. No tokens, content, or raw downstream bodies are ever logged.

## Things to watch out for

1. **Attachment is the #1 integration decision.** An uploaded CDAM document that never gets
   attached to a case is **TTL-disposed** — links die silently. Either set
   `ccd.bundling.cdam.attach-to-case: true` (requires your S2S identity to hold CDAM's `ATTACH`
   permission in its service config, and a `caseReference` on every execution context), or ensure
   `result.output()`'s `stitchedDocument` (which carries `document_hash`) flows through a CCD
   case-data submission so ccd-data-store attaches it. Doing neither reproduces the exact defect
   this flag exists to fix.
2. **Case reference must be exactly 16 digits** when attaching — validated before upload, so a
   malformed ref fails fast without orphaning.
3. **Attach is at-least-once.** A crash between a successful attach and your case update can
   leave an attached-but-unreferenced duplicate document on the case. Harmless but real; your
   case data only ever references the bundle you recorded.
4. **`STORAGE_REJECTED` vs `STORAGE_FAILED`**: 4xx destination rejections (missing ATTACH
   permission, validation) are permanent and never retried by the job runner; 5xx/IO are transient
   and retried. Alert on `STORAGE_REJECTED` — retrying can't fix it.
5. **`CcdBundle` is a wire shape, not an importable CCD complex type.** Definition stores reject
   the recursive folder type. Keep a bounded bundle model in your case definition (as sptribs
   does) and persist the JSON into it.
6. **Decentralised submit handlers don't persist `caseData` mutations.** Write the bundle through
   your repository/persistence path and surface errors via `SubmitResponse.errors` — letting the
   exception propagate reaches the caseworker as a bare 502.
7. **Lock down the case field.** The bundle collection's ACLs govern who sees the stitched
   document; a default caseworker access class may grant citizen/solicitor READ. Stitching
   collapses per-document sensitivity into one field — classify accordingly (and
   `classification` on upload is deliberately mandatory).
8. **Media documents**: `MediaPlaceholder` needs `mediaType` (routes the handler) and an absolute
   `accessUrl`; the file itself is never fetched. Prefer stable case-scoped links over expiring
   signed URLs — the page outlives the URL otherwise.
9. **Office conversion needs Docmosis config**, or a bundle containing a Word/Excel/PowerPoint
   document fails with `DOCMOSIS_NOT_CONFIGURED` (the message names the properties). †SVG is
   registered for parity but PDFBox cannot decode it — an actual SVG fails at CONVERT, same as
   the current service.
10. **Non-Latin titles**: drawn TOC/coversheet text is WinAnsi — non-encodable characters are
    dropped (a fully non-encodable title falls back to `"Document <n>"` + a
    `TITLE_NOT_RENDERABLE` warning); bookmarks keep the full Unicode title.
11. **Job submitters should run READ COMMITTED.** Under REPEATABLE READ a concurrent duplicate
    submit gets the existing job back but PostgreSQL has already doomed the caller's transaction.
12. **Keep the worker's lease longer than `maxElapsed`.** Lease expiry mid-render is recoverable
    (terminal writes are lease-owner-guarded; only the holder's publish is recorded) but means a
    double render.
13. **`COMPLETED_WITH_WARNINGS` is not plain success** — surface the warnings (empty-section page
    included, no extractable text, truncated outline…) in your UI/audit; they never mean an
    omitted document.
14. **Retention/deletion stays yours.** The SDK returns references and a report; deleting a court
    bundle is a consumer workflow, never an SDK concern.
15. **Testing**: fake `BundleRenderer` (it's an interface) for event-handler unit tests; use
    `FilesystemBundleDestination` + a stub `DocmosisRenderService` for integration tests without
    platform services; PDFBox is implementation-scoped, so declare it yourself if your tests
    parse PDFs. The e2e project (`test-projects/e2e`, `uk.gov.hmcts.divorce.bundling`) is the
    reference integration.

## Error catalogue (stable, alertable)

| Code | Stage | Retried by job runner |
|---|---|---|
| `REQUEST_INVALID` | VALIDATE | no |
| `MEDIA_TYPE_UNSUPPORTED` / `DOCMOSIS_NOT_CONFIGURED` | VALIDATE/CONVERT | no |
| `DOCUMENT_NOT_FOUND` / `DOCUMENT_ACCESS_DENIED` | RESOLVE | no |
| `DOCUMENT_RESOLUTION_FAILED` | RESOLVE | yes (bounded) |
| `DOCUMENT_CONTENT_INVALID` | CONVERT | no |
| `DOCUMENT_CONVERSION_FAILED` | CONVERT | yes (bounded) |
| `DOCUMENT_INSPECTION_FAILED` | INSPECT | no |
| `LIMIT_EXCEEDED` | any | no |
| `ASSEMBLY_FAILED` / `OUTPUT_VALIDATION_FAILED` | ASSEMBLE/STORE | no |
| `STORAGE_FAILED` | STORE | yes (bounded) |
| `STORAGE_REJECTED` | STORE | **no** |
| `TIMED_OUT` | any | yes (bounded) |
| `JOB_REQUEST_UNREADABLE` | worker | no |
