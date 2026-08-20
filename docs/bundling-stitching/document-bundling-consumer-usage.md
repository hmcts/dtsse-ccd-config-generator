# Document Bundling SDK: Consumer Usage

**Status:** Draft, tracking the API published in `sdk/document-bundling`

**Companions:** [the module design](document-bundling-module-design.md) ·
[low-level design](document-bundling-lld.md) ·
[developer reference](document-bundling-for-devs.md)

This document shows what integrating the document-bundling module looks like from a consuming
service's point of view. The worked example targets the `e2e` test project
(`test-projects/e2e`, package `uk.gov.hmcts.divorce`), which is the preferred place to exercise
new SDK features with automated full-stack tests; a migration sketch for `sptribs-case-api`
follows it.

## The shape of an integration

A consumer supplies one port and receives a renderer:

1. Implement `DocumentResolver` — turn your opaque `DocumentReference`s into content, in one
   authorised batch.
2. Build one `BundleRenderer` (typically a Spring bean).
3. Per bundle: build a `BundleRequest` from your case data, call `render`, and attach
   `result.output()` to your case through your normal event mechanism. The output is a
   `CcdBundle` — the platform-standard bundle shape, JSON-compatible with the orchestrator's
   `CcdBundleDTO` and with the bundle model your `caseBundles` field already holds, so existing
   case definitions and the XUI bundle presentation need no change. (Your case field keeps its
   own bounded bundle model: CCD complex types cannot be recursive, so `CcdBundle` itself is not
   importable as a CCD complex type — see "The case field and its model" below.)

Where the PDF itself goes is not a decision you make: **the finished artifact is always uploaded
to CDAM** (centralised blob storage) by the SDK's built-in destination, exactly as the current
stitching service stores its output. What only you know is where the resulting bundle *metadata*
lives — which case field, category, classification, and ACLs.

Everything else — validation, conversion, table of contents, bookmarks, cover sheets, page
numbers, confidential markings, media link pages, the generation report, typed errors — is the
module's job. Your case model is never copied into a stitching DTO, and no call leaves your
service except document resolution you own, the CDAM upload, and (optionally) Docmosis office
conversion.

## 1. Add the dependency

```groovy
dependencies {
    implementation 'com.github.hmcts:document-bundling:<version>'
}
```

Inside this repository the composite build substitutes the local module, so test projects use the
same coordinates with version `DEV-SNAPSHOT` (see how `test-projects/e2e/build.gradle` consumes
both `com.github.hmcts:task-management:DEV-SNAPSHOT` and
`com.github.hmcts:document-bundling:DEV-SNAPSHOT`). PDFBox is an `implementation` dependency of
the module — on your runtime classpath but not your compile classpath — so declare
`org.apache.pdfbox:pdfbox` yourself if your own code manipulates PDFs (the e2e project does, for
its Docmosis stub and its semantic PDF assertions).

## 2. Implement the resolver port

### DocumentResolver

The resolver receives every unique reference for the bundle in **one call**, so it can authorise
and fetch the whole set efficiently. Failures are reported per reference with typed reasons — the
SDK turns them into an error naming each responsible document. Never let raw downstream error
bodies or tokens into the result.

```java
@Component
public class CaseDocumentResolver implements DocumentResolver {

    public static final String PROVIDER = "case-documents";

    private final CaseDocumentStore store; // however your service reads its documents

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public ResolvedDocuments resolveAll(
            List<DocumentReference> references, BundleExecutionContext context) {
        Map<DocumentReference, ResolvedDocument> resolved = new LinkedHashMap<>();
        Map<DocumentReference, ResolutionFailure> failures = new LinkedHashMap<>();
        for (DocumentReference reference : references) {
            store.findBinary(reference.id())
                .ifPresentOrElse(
                    binary -> resolved.put(reference, binary.asResolvedDocument()),
                    () -> failures.put(reference, new ResolutionFailure(
                        ResolutionFailureReason.NOT_FOUND, "No document with this id")));
        }
        return new ResolvedDocuments(resolved, failures);
    }
}
```

Contract highlights (the adapter contract tests in the testing strategy pin these):

* One batch call per job; the SDK has already deduplicated references.
* `ResolvedDocument.content()` is read once and spooled by the SDK; streams are closed for you.
* Background/scheduled bundling authorises with your service's **system user**, never a captured
  end-user token, and nothing in `BundleExecutionContext` may be secret.
* `ACCESS_DENIED` fails the bundle just like `NOT_FOUND` — do not soften it.

### Output storage: not a port you implement

The finished PDF is always uploaded to CDAM by the SDK's built-in CDAM destination — artifact
storage is an invariant of the platform (the current stitching service stores its output the same
way, and consumers like sptribs already hold the result as a CCD `Document`). The destination is
called exactly once, only after the whole bundle has rendered and validated, and returns only
after storage succeeded — a failed job never replaces the last good bundle.

**The upload alone is not durable: the document must be attached to the case.** CDAM disposes of
a document that is never associated with a case once its TTL expires — an uploaded-but-unattached
bundle's links die silently. Attachment (a `case_id` on the stored document) happens one of two
ways, and which one applies is decided by your event style:

* **Legacy callback events**: leave `ccd.bundling.cdam.attach-to-case` off (the default) and
  write `CcdBundle.stitchedDocument` — whose `document_hash` carries the CDAM hash token — into
  case data through the callback response. The platform scans it and performs the attach
  (ccd-data-store as `ccd_data` for centralised services; the decentralised runtime's
  `CdamAttachService` for legacy-callback events on decentralised services). Your service needs
  no CDAM `ATTACH` permission in the centralised model — and could not attach itself anyway.
* **Decentralised submit-handler events**: set `ccd.bundling.cdam.attach-to-case: true`. Your
  submit handler owns persistence, so no platform component ever sees the document in case data
  and nothing would attach it. With the flag set, the CDAM destination itself calls CDAM's
  `attachToCase` immediately after the upload, using the hash token from the upload and
  `BundleExecutionContext.caseReference` (required in this mode — the destination refuses to
  upload without it rather than create an orphan). This requires your service's S2S identity to
  be onboarded with CDAM `ATTACH` permission, exactly as the decentralised runtime's
  `ccd.decentralised-runtime.cdam-attach` feature already requires.

Two things remain yours:

* **The upload classification is explicit configuration.** Unlike the current service, which
  hardcodes `PUBLIC`, the CDAM destination refuses to guess: a bundle containing restricted
  material must never default to public.
* **The metadata.** The render output is a `CcdBundle` carrying the stitched document's CDAM
  links (`document_url`/`document_binary_url`/`document_filename`/`document_hash`), the
  documents/folders echo, and `stitchStatus` — deliberately JSON-compatible with the
  `CcdBundleDTO` shape every consumer's `caseBundles` field already deserialises
  (`@JsonIgnoreProperties(ignoreUnknown = true)` on both sides). Which case field it lands in,
  its category, and its field-level ACLs are your case definition's concern.

In tests and local runs the `BundleDestination` port lets you substitute the SDK's filesystem
destination, so nothing here requires CDAM to be running.

## 3. Construct the renderer

One bean, built once. Defaults reproduce the current stitching microservice's output. In a Spring
Boot service the auto-configuration (`BundlingAutoConfiguration`, active as soon as the module is
on the classpath) assembles the renderer for you; the manual builder below remains for non-Spring
and test use.

### The auto-configuration path

Set the properties, binding Docmosis to whichever environment variables your service already
holds (`DOCMOSIS_*` in EM-style services, `TORNADO_*` in ET-style services):

```yaml
ccd:
  bundling:
    docmosis:
      convert-endpoint: ${DOCMOSIS_ENDPOINT}         # .../rs/convert
      render-endpoint: ${DOCMOSIS_RENDER_ENDPOINT}   # .../rs/render
      access-key: ${DOCMOSIS_ACCESS_KEY}
    cdam:
      jurisdiction-id: DIVORCE
      case-type-id: NFD
      classification: RESTRICTED   # explicit by design; never defaulted
      attach-to-case: true         # decentralised submit-handler events only; see "Output storage"
```

and define the one bean the SDK cannot guess — the system-user authentication port the CDAM
adapters present to downstream services. It is deliberately consumer-provided: token wiring is
service-specific (your IDAM client, your system-user credentials, your S2S microservice name),
and the SDK must not invent property names for credentials it never persists:

```java
@Bean
public BundlingAuthenticationProvider bundlingAuthenticationProvider(
        IdamClient idam, AuthTokenGenerator s2s) {
    return new BundlingAuthenticationProvider() {
        public String systemUserToken() { return idam.getAccessToken(user, password); }
        public String serviceToken() { return s2s.generate(); }
    };
}
```

With those, your service's `CaseDocumentClientApi` bean, and your `DocumentResolver` bean(s), the
auto-configuration registers the Docmosis client, the CDAM destination and resolver, and a
`BundleRenderer` built from every resolver and `BundlingExtension` bean in the context (extensions
apply in `@Order` order), your `MeterRegistry` when one exists, and `ccd.bundling.limits.*` /
`max-concurrent-renders` / `temp-directory` when set. Every bean backs off to one you define
yourself, `ccd.bundling.enabled=false` switches the whole thing off, and the durable job runner's
worker picks the renderer up automatically (see "Durable execution").

* **Without the three `ccd.bundling.docmosis.*` connection properties** no Docmosis client is
  registered and office media types (Word, Excel, PowerPoint, RTF, plain text) have no handler —
  a bundle containing one fails with an error that says exactly that and names the properties to
  set. PDF, images, and MP3/MP4 always work.
* **Without the CDAM pieces** (the two beans plus all three `ccd.bundling.cdam.*` properties) the
  CDAM destination and resolver back off. A partially configured CDAM block — some but not all
  properties, or all properties with a bean missing — is called out at WARN on startup, naming
  exactly which keys and beans are absent; setting none of the properties stays silent. If a
  resolver bean exists but no destination at all, startup fails with an error saying exactly
  what to provide.

### The manual builder

```java
@Configuration
public class BundlingConfig {

    @Bean
    public BundleRenderer bundleRenderer(
            CaseDocumentResolver resolver,
            BundleDestination cdamDestination,  // SDK-provided; uploads every artifact to CDAM
            DocmosisRenderService docmosis) {   // omit if you have no office documents
        return BundleRenderer.builder()
            .resolver(resolver)
            .destination(cdamDestination)
            .docmosis(docmosis)
            .build();
    }
}
```

* `renderer.handledMediaTypes()` tells you (and your tests) exactly what the effective registry
  supports.
* Bound by default: `maxConcurrentRenders(2)` and `BundleLimits.defaults()` (300 MB per source,
  1 GB output, 1,000 pages, one-minute end-to-end timeout) — override with `.limits(...)` (or
  `ccd.bundling.limits.*`, field by field, on the auto-configured path) if you have evidence you
  need to.

## The case field and its model

CCD complex types cannot be recursive, so the SDK's `CcdBundle` — whose `CcdBundleFolder` nests
itself — cannot be imported as a CCD complex type (definition import fails with
`No type found for collection of: CcdBundleFolder`). This is not a regression: the platform's
bundle definitions have always bounded the folder tree (the orchestrator's YAML models
`folders` → `subfolders` to a fixed depth), and every existing consumer's `caseBundles` field
already holds a bounded model of its own.

So the case field keeps a **consumer-owned bundle model**, JSON-compatible with `CcdBundle`
(both sides are `@JsonIgnoreProperties(ignoreUnknown = true)`): sptribs' is `Bundle`; the e2e
project's is `uk.gov.hmcts.divorce.bundling.model.CaseBundle` (with `CaseBundleFolder`,
`CaseBundleSubfolder`, `CaseBundleDocument`), declared on `CaseData` as

```java
@CCD(
    label = "Case bundles",
    typeOverride = FieldType.Collection,
    typeParameterOverride = "CaseBundle",
    access = {CaseworkerAccess.class}
)
@External
private List<ListValue<CaseBundle>> caseBundles;
```

`result.output()` converts straight into that model with Jackson
(`objectMapper.convertValue(result.output(), CaseBundle.class)`, or serialise/deserialise as the
e2e bundle store does); folder nesting deeper than the model is dropped on conversion.

## 4. Trigger from a CCD event or a task scheduler, and attach the result

How the result is attached depends on your event style (the document-attachment half of this —
who PATCHes `case_id` onto the CDAM document — is covered under "Output storage" above):

* **Legacy callback events**: run the same request/render code in an `aboutToSubmit` callback and
  write the converted bundle into case data through the callback's response — the platform
  persists it and attaches the stitched document from its `document_hash`.
* **Decentralised events**: a submit handler's mutations to `payload.caseData()` are *not*
  persisted to the case-data blob — the decentralised model is that the service owns its data.
  Persist the bundle in your own table inside the submit transaction and project it into the
  case through your `CaseView`, exactly as the e2e project's case notes work. Because nothing
  platform-side sees that data, set `ccd.bundling.cdam.attach-to-case: true` and pass
  `BundleExecutionContext.caseReference` so the destination attaches the stitched document
  itself.

The real e2e integration (package `uk.gov.hmcts.divorce.bundling`, verified by
`TestWithCCD.createBundleStitchesPublishesToCdamAndAttachesToCase`):

```java
@Component
@Slf4j
public class CaseworkerCreateBundle implements CCDConfig<CaseData, State, UserRole> {

    public static final String CASEWORKER_CREATE_BUNDLE = "caseworker-create-bundle";

    @Autowired
    private BundleRenderer bundleRenderer;

    @Autowired
    private CaseBundleRepository caseBundleRepository;

    @Override
    public void configureDecentralised(
            final DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        configBuilder
            .decentralisedEvent(CASEWORKER_CREATE_BUNDLE, this::submit)
            .forAllStates()
            .name("Create hearing bundle")
            .grant(CREATE_READ_UPDATE, CASE_WORKER, SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE);
    }

    private SubmitResponse<State> submit(EventPayload<CaseData, State> payload) {
        final long reference = payload.caseReference();

        BundleRequest request = BundleRequest.builder()
            .externalId(UUID.randomUUID()) // mint & persist: your idempotency/versioning decision
            .title("Hearing bundle")
            .fileName("case-" + reference + "-hearing-bundle.pdf")
            .root(BundleSection.builder("Case file")
                .section(BundleSection.builder("Applications")
                    .document(BundleDocument.builder()
                        .id("app-1")
                        .title("Potential energy application")
                        .date(LocalDate.of(2026, 1, 12))
                        .reference(new DocumentReference(
                            FixtureDocumentResolver.PROVIDER, "potential-energy-pdf"))
                        .build())
                    // ... more documents: a second PDF, and a .docx that round-trips
                    // through the Docmosis office conversion
                    .build())
                .section(BundleSection.builder("Evidence")
                    .document(/* an image/jpeg document */)
                    .document(/* an audio/mpeg MediaPlaceholder document, see below */)
                    .build())
                .section(BundleSection.builder("Correspondence")
                    .emptySectionPolicy(EmptySectionPolicy.INCLUDE_PLACEHOLDER)
                    .build())
                .build())
            .build(); // presentation defaults to BundlePresentation.courtDefault()

        BundleExecutionContext context = BundleExecutionContext.builder()
            .caseReference(String.valueOf(reference))
            .initiator(CASEWORKER_CREATE_BUNDLE)
            .build();

        // Catch nothing: a BundleGenerationException propagates, the platform rolls the event
        // back (the bundle store insert below is in the same transaction), nothing was
        // published, and the previous bundle is untouched.
        BundleResult result = bundleRenderer.render(request, context);

        caseBundleRepository.save(reference, request.externalId().toString(), result.output());

        log.info("Bundle {} generated: {} pages, {} warnings",
            request.externalId(), result.pageCount(), result.warnings().size());
        return SubmitResponse.<State>builder()
            .confirmationHeader("Hearing bundle created")
            .confirmationBody("Generated " + result.pageCount() + " pages")
            .build();
    }
}
```

`CaseBundleRepository` stores `result.output()` as JSONB in a service-owned `case_bundles` table
and reads it back as the service's `CaseBundle` model; `NFDCaseView` sets
`caseData.setCaseBundles(caseBundleRepository.findByCase(caseRef))` when CCD loads the case.

**Surfacing failures to the caseworker.** A propagated exception rolls the event back but
reaches the user only as a generic callback error. When the caseworker should see the
diagnostics, catch the typed exception and return its message as a platform error — the event is
still rolled back and nothing is published. The e2e failure-path event
(`CaseworkerCreateBundleMissingDocument`) does exactly this:

```java
try {
    BundleResult result = bundleRenderer.render(request, context);
    caseBundleRepository.save(reference, request.externalId().toString(), result.output());
} catch (BundleGenerationException e) {
    // Stable code, stage, each responsible document, remediation — caseworker-grade.
    return SubmitResponse.<State>builder().errors(List.of(e.getMessage())).build();
}
```

### What the caseworker gets by default

`BundlePresentation.courtDefault()` produces: a title page, a clickable table of contents with
titles/dates/start pages, PDF bookmarks mirroring the tree, section cover sheets, `N of M` page
numbers centred in the footer, the approved header marking on confidential documents, and a
visible placeholder page for expected-but-empty sections. Adjust only through the preset's
withers — arbitrary text, fonts, and coordinates over evidence pages are deliberately not
expressible.

## Audio and video documents

MP3/MP4 are ordinary bundle documents carrying a `MediaPlaceholder`; the SDK renders each as a
standard, accessible link page (title, date, media type, optional duration and note, clickable
link). The file itself is never fetched — a 2 GB recording costs nothing to bundle.

```java
BundleDocument.builder()
    .id(recording.getId())
    .title("Hearing recording, day 2")
    .date(recording.getDate())
    .reference(new DocumentReference(CaseDocumentResolver.PROVIDER, recording.getId()))
    .media(MediaPlaceholder.builder()
        .mediaType("audio/mpeg")                    // required at render: routes the handler
        .accessUrl(recording.getPlaybackUrl())      // required; must be absolute
        .duration(recording.getDuration())          // optional
        .note("Playback requires case access")      // optional, ≤500 chars
        .build())
    .build();
```

Choose the access URL deliberately: a signed URL that expires before the bundle is read is worse
than a stable case-scoped link.

## Customising per-media-type behaviour

Extensions follow the Jackson `Module` pattern and apply at build time, failing fast on mistakes
(adding a type that's already handled, replacing one that isn't) with the extension and media
type named in the error:

```java
BundleRenderer.builder()
    .resolver(resolver)
    .destination(destination)
    .extension(new BundlingExtension() {
        @Override
        public String name() {
            return "et-media-pages";
        }

        @Override
        public void configure(BundlingExtensionContext context) {
            context.replaceHandler("video/mp4", new EtBrandedMediaLinkHandler());
            context.addHandler("application/vnd.ms-outlook", new MsgToPdfHandler());
        }
    })
    .build();
```

Handlers implement `DocumentHandler` and receive bounded services (`HandlerContext`: temp files,
the Docmosis client when configured, the effective limits) — never the assembler, so a custom
handler cannot break bundle-wide invariants.

## When it fails

There are no partial bundles: any document that cannot be stitched fails the whole job and
publishes nothing. The module's obligation shifts to diagnostic precision — everything it logs
goes through your logger and therefore lands in **your** Application Insights:

```text
DOCUMENT_NOT_FOUND at stage RESOLVE: 2 of 12 documents could not be resolved.
Failed documents: app-1 (case-documents/abc-123): DOCUMENT_NOT_FOUND - No document with this id;
app-2 (case-documents/def-456): DOCUMENT_NOT_FOUND - No document with this id.
Remediation: Check the documents still exist in the case, then resubmit the bundle.
```

`BundleGenerationException` exposes the same facts as fields: a stable `BundleErrorCode` (safe to
alert on), the `BundleStage`, per-document `DocumentFailure`s, and a remediation hint. Warnings on
a successful result (`COMPLETED_WITH_WARNINGS`) are presentational notes only — they never mean a
document was omitted.

## Durable execution (interfaces published; implementation is a later phase)

When you need one-click **and** scheduled generation without holding a callback open, submit
through `BundleJobService` instead of calling the renderer directly. Submission inserts an outbox
row in your current transaction; `externalId` is the idempotency key, so a double click returns
the existing job.

```java
// In the event handler: snapshot at submission
BundleJob job = bundleJobService.submit(request, context);

// Or submit only parameters and compile the document list when the job runs
// (snapshot at execution — documents arriving in between are included):
BundleJob job = bundleJobService.submit(
    externalId, Map.of("hearingId", hearing.getId()), context);

@Bean
BundleDocumentSelector bundleDocumentSelector() {
    return jobContext -> buildRequestForHearing(jobContext.parameters().get("hearingId"));
}
```

Job states: `QUEUED → RESOLVING → CONVERTING → ASSEMBLING → STORING →
COMPLETED | COMPLETED_WITH_WARNINGS | FAILED`, with progress events carrying completed/total
document counts. If your service already has a reliable job mechanism, skip all of this and call
`BundleRenderer` from it.

**Switching the runner off, and the outbox schema.** The job runner is opt-out as a whole:
`ccd.bundling.job.enabled: false` backs off every outbox bean — repository, service, worker, and
migration — so a service that renders synchronously carries no `ccd_bundle_job` table and nothing
that could touch one (`ccd.bundling.job.worker.enabled: false` remains available to suppress just
the polling worker, e.g. on non-worker replicas). When the runner is enabled, its schema
(`classpath:document-bundling-db/migration`) is applied automatically on startup through a
module-owned Flyway instance with its own history table (`ccd_bundle_job_flyway_history`) that
baselines on migrate at version 0 — your schema already holds your application's tables, and the
baseline accepts it while still applying the module's `V0001`. It is deliberately *not* an
addition to your application's Flyway locations (version numbers would collide with the
module's), and deliberately *not* the decentralised runtime's idiom either — that runtime
migrates a dedicated `ccd` schema through the context's single `FlywayMigrationStrategy` bean,
which this module leaves free for it. It needs Flyway on the classpath and a `DataSource`; set
`ccd.bundling.job.auto-migrate: false` to manage the table from your own migrations instead
(copy `document-bundling-db/migration/V0001__ccd_bundle_job.sql`). One ordering caveat: the
migration runs as its bean initialises during context refresh, which is early enough for the
worker's scheduled polling, but a bean of yours that queries the outbox from its own
initialisation callback should declare `@DependsOn("bundleJobFlywayMigration")`.

## Migrating sptribs-case-api (sketch)

Today sptribs posts `/api/new-bundle` to `em-ccd-orchestrator` via a Feign `BundlingClient`,
injects `st_cic_bundle_all_case.yaml`, and receives the stitched document later through the
hidden `asyncStitchingComplete` event. With the SDK:

* The `createBundle` event builds a `BundleRequest` directly from the same case-document
  filtering code that currently populates the temporary bundle fields — no YAML, no
  `CcdBundleDTO`, no hidden completion event; the result is attached in the same submit.
* Their `caseBundles` field already deserialises exactly this shape — sptribs' `Bundle` model is
  `@JsonIgnoreProperties(ignoreUnknown = true)` and mirrors `CcdBundleDTO` field for field — so
  `result.output()` drops straight in (or
  `objectMapper.convertValue(result.output(), Bundle.class)` to stay on their own type). The PDF
  itself still lands in CDAM, as it does today.
* `CaseworkerCICDocument.isValidBundleDocument` currently drops `mp3`/`m4a`/`mp4` silently —
  those become `MediaPlaceholder` documents instead, closing the audio/video gap.
* The existing cover-page template (`ST-CIC-ASS-ENG-Cover-Page.docx`) is carried through the
  Docmosis-backed template support during migration.
* A legacy adapter mapping the current `CcdBundleDTO` tree to `BundleRequest` is planned
  (design: Phase 2) for consumers that want to keep selection code unchanged initially.

## Testing your integration

* `BundleRenderer` is an interface: unit-test event handlers against a fake that records the
  request and returns a canned `BundleResult`.
* Contract-test your resolver: it batches once, maps typed failures, and never leaks auth
  material. (The CDAM destination's atomicity and explicit-classification contract is tested by
  the SDK itself.)
* Functional coverage follows [the SDK testing strategy](../testing-strategy.md): Cftlib tests
  that run the real event against the embedded CCD stack and assert on the case, the stored
  output, and failure surfacing.

## How this feature is verified in this repository (e2e project)

The `e2e` test project is the preferred vehicle for automated verification of new SDK features.
The integration as built (all in `test-projects/e2e` unless noted):

1. `build.gradle` adds `com.github.hmcts:document-bundling:DEV-SNAPSHOT` (substituted with the
   local module by the composite build) and `org.apache.pdfbox:pdfbox` (for the Docmosis stub and
   the semantic PDF assertions).
2. Package `uk.gov.hmcts.divorce.bundling`:
   * `CaseworkerCreateBundle` — the decentralised event above: three sections (two fixture PDFs
     plus a `.docx` in "Applications"; a JPEG and an `audio/mpeg` `MediaPlaceholder` in
     "Evidence"; an empty "Correspondence" with `INCLUDE_PLACEHOLDER`), rendered synchronously,
     stored via `CaseBundleRepository` (a `case_bundles` JSONB table, migration
     `V0004__case_bundles.sql`) and projected into `CaseData.caseBundles` by `NFDCaseView`.
   * `CaseworkerCreateBundleMissingDocument` — the failure path: references a document the
     resolver does not know, maps the `BundleGenerationException` message onto
     `SubmitResponse.errors`.
   * `FixtureDocumentResolver` — the `DocumentResolver` port (provider `case-documents`), serving
     the module's own fixture files from `src/main/resources/bundling-fixtures/`; unknown ids →
     typed `NOT_FOUND`.
   * `BundlingConfiguration` — the `BundlingAuthenticationProvider` bean, backed by the app's
     existing `IdamService` system user (`idam.systemupdate.*`) and `AuthTokenGenerator`
     (`nfdiv_case_api`).
   * `model.CaseBundle` (+ folder/subfolder/document) — the consumer-owned bounded bundle model
     (see "The case field and its model").
3. Stubs hosted by the app itself (`uk.gov.hmcts.divorce.stubs` / `bundling`), preserving the
   "runs locally without shared services" goal:
   * `DocmosisStubController` — `/stub/rs/convert` and `/stub/rs/render`, accepting the module's
     real multipart protocol, answering with a generated PDF that names the converted source,
     and recording every conversion in-process so tests assert on this run's calls rather than
     grepping the append-only traffic log.
   * `DmStoreStubController` + `StubDocumentStore` — cftlib points the embedded
     ccd-case-document-am-api at this app as its dm-store (`DM_STORE_BASE_URL=localhost:4013`),
     so CDAM's blob upload (`POST /documents`), metadata read (`GET /documents/{id}`), binary
     download, and attach-time metadata `PATCH /documents` (which writes `case_id`) all land on
     the one `StubDocumentStore` record (`spring.servlet.multipart.max-*-size` raised
     accordingly). The TTL the stub reports is realistic (upload time plus a bounded window) and
     the stub serves an expired, never-attached document as 404 — though within a test run
     nothing expires, so the genuine attachment proof is the `case_id` metadata assertions made
     through the real CDAM, not the TTL.
     The upload and attach go through the **real CDAM instance** — service permissions, audit,
     hash-token verification — with only the blob store behind it stubbed. CDAM honours only the
     first `authorisedServices` entry per service id, so
     `src/cftlib/resources/cdam-service-config-override/service_config.json` holds one
     wildcard-jurisdiction `nfdiv_case_api` entry (including `ATTACH`).
4. Configuration (`application.yaml`): `ccd.bundling.docmosis.*` pointing at the app's own stub,
   `ccd.bundling.cdam.jurisdiction-id: DIVORCE` / `case-type-id: E2E` / `classification: PUBLIC`
   / `attach-to-case: true` (the event is a decentralised submit handler, so the destination
   must attach the upload itself), and `ccd.bundling.job.enabled: false` (rendering is
   synchronous here; the umbrella flag backs off every outbox bean and the module's own
   `ccd_bundle_job` migration).
5. `TestWithCCD` (the monolithic cftlib suite — one Spring context owns the app's port) drives
   the events through real CCD as a caseworker:
   * `createBundleStitchesPublishesToCdamAndAttachesToCase` — event succeeds; `caseBundles`
     holds one bundle with `stitchStatus: DONE`, the right title/fileName, CDAM document links
     and the folders echo; **proves the attach**: the dm-store record's metadata now carries
     `case_id` equal to the case reference (with the configured case type and jurisdiction) and
     CDAM's own metadata read reports the document as case-attached; downloads the stitched
     binary back **through CDAM**, now authorised via case visibility rather than the
     unattached-TTL window; asserts semantically on the PDF with PDFBox (page count, extracted
     text for every section/document title, the Docmosis-stub conversion marker, the media link
     page's title/type/note/URL, the empty-section placeholder, and the bookmark tree); asserts
     the Docmosis stub converted exactly this run's `.docx`; and checks the HTTP traffic log for
     the CDAM upload carrying this run's fresh document id.
   * `createBundleWithMissingDocumentSurfacesErrorAndPublishesNothing` — 422 whose callback error
     names `DOCUMENT_NOT_FOUND`, the document id and the provider; the bundle store and the
     case's collection are unchanged.
6. Run with `./gradlew -i e2e:cftlibTest` (JDK 21).

Rendering parity with the current microservice is separately pinned by characterisation goldens generated from the
local `em-stitching-api` checkout — see
[the regression baseline](document-bundling-module-design.md#regression-baseline-characterisation-against-the-local-em-stitching-api)
in the design document.
