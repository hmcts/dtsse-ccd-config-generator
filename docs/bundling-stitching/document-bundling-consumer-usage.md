# Document Bundling SDK: Consumer Usage

**Status:** Draft, tracking the API published in `sdk/document-bundling`

**Companion to:** [the module design](document-bundling-module-design.md)

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
   case definitions and the XUI bundle presentation need no change.

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
same coordinates with version `DEV-SNAPSHOT` (see how `test-projects/e2e/build.gradle` already
consumes `com.github.hmcts:task-management:DEV-SNAPSHOT`).

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

One bean, built once. Defaults reproduce the current stitching microservice's output.

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

* **Without `docmosis(...)`** office media types (Word, Excel, PowerPoint, RTF, plain text) have
  no handler, and a bundle containing one fails with an error that says exactly that. PDF,
  images, and MP3/MP4 always work.
* Docmosis binds to the endpoint/access-key properties your service already holds
  (`DOCMOSIS_*` / `TORNADO_*`); the Spring auto-configuration (later phase) will register the
  client and the renderer bean for you from `ccd.bundling.docmosis.*` properties.
* `renderer.handledMediaTypes()` tells you (and your tests) exactly what the effective registry
  supports.
* Bound by default: `maxConcurrentRenders(2)` and `BundleLimits.defaults()` (300 MB per source,
  1 GB output, 1,000 pages, one-minute end-to-end timeout) — override with `.limits(...)` if you
  have evidence you need to.

## 4. Trigger from a CCD event or a task scheduler, and attach the result

A full event class in the e2e project's style (package
`uk.gov.hmcts.divorce.bundling`):

```java
@Component
@Slf4j
public class CaseworkerCreateBundle implements CCDConfig<CaseData, State, UserRole> {

    public static final String CASEWORKER_CREATE_BUNDLE = "caseworker-create-bundle";

    @Autowired
    private BundleRenderer bundleRenderer;

    @Override
    public void configureDecentralised(
            final DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        configBuilder
            .decentralisedEvent(CASEWORKER_CREATE_BUNDLE, this::submit)
            .forAllStates()
            .name("Create hearing bundle")
            .grant(CREATE_READ_UPDATE, CASE_WORKER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE);
    }

    private SubmitResponse<State> submit(EventPayload<CaseData, State> payload) {
        var caseData = payload.caseData();

        BundleRequest request = BundleRequest.builder()
            .externalId(UUID.randomUUID()) // mint & persist: your idempotency/versioning decision
            .title("Hearing bundle")
            .fileName("case-" + payload.caseReference() + "-hearing-bundle.pdf")
            .root(BundleSection.builder("Case file")
                .section(BundleSection.builder("Applications")
                    .documents(caseData.getApplicationDocuments().stream()
                        .sorted(comparing(ApplicationDocument::getDate))
                        .map(doc -> BundleDocument.builder()
                            .id(doc.getId())
                            .title(doc.getTitle())
                            .date(doc.getDate())
                            .reference(new DocumentReference(
                                CaseDocumentResolver.PROVIDER, doc.getId()))
                            .confidential(doc.isConfidential())
                            .build())
                        .toList())
                    .emptySectionPolicy(EmptySectionPolicy.INCLUDE_PLACEHOLDER)
                    .build())
                .build())
            .build(); // presentation defaults to BundlePresentation.courtDefault()

        BundleExecutionContext context = BundleExecutionContext.builder()
            .caseReference(String.valueOf(payload.caseReference()))
            .initiator(CASEWORKER_CREATE_BUNDLE)
            .build();

        BundleResult result = bundleRenderer.render(request, context);
        // Throws BundleGenerationException on any failure: nothing was published, the previous
        // bundle is untouched, and the exception/logs name each responsible document. Let it
        // propagate: the platform surfaces the error to the caseworker and nothing is saved.

        caseData.getCaseBundles().add(
            new ListValue<>(request.externalId().toString(), result.output()));

        log.info("Bundle {} generated: {} pages, {} warnings",
            request.externalId(), result.pageCount(), result.warnings().size());
        return SubmitResponse.<State>builder()
            .confirmationHeader("Hearing bundle created")
            .build();
    }
}
```

For a service that is not decentralised, the same request/render code runs in an
`aboutToSubmit`/submitted callback, and the bundle reference is written into case data through
that callback's response.

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
The planned setup, mirroring how other SDK modules are exercised there:

1. `test-projects/e2e/build.gradle` adds `com.github.hmcts:document-bundling:DEV-SNAPSHOT`
   (substituted with the local module by the composite build).
2. A `uk.gov.hmcts.divorce.bundling` package adds the `CaseworkerCreateBundle` event above and a
   resolver backed by fixture documents (real PDFs, images, an office document, an MP3
   placeholder). Output goes through the SDK's CDAM destination against the real CDAM instance
   cftlib boots — exercising the storage invariant end to end — with the filesystem destination
   available for narrower tests.
3. Docmosis is stubbed with a small controller in the e2e app (the existing
   `RefDataStubController` is the pattern), so office conversion is exercised without the shared
   service — preserving the "runs locally without Docmosis/DM-store" goal.
4. A `cftlibTest` drives the event through real CCD as a caseworker and asserts semantically on
   the produced PDF (page count, extracted text, bookmarks, links, page labels — never
   byte-for-byte), on the case data holding the stored reference, and on failure cases naming the
   right documents. `build/logs/http-traffic.log` captures every callback and persistence call
   for debugging.
5. Run with `./gradlew -i e2e:cftlibTest` (JDK 21).

Rendering parity with the current microservice is separately pinned by characterisation goldens generated from the
local `em-stitching-api` checkout — see
[the regression baseline](document-bundling-module-design.md#regression-baseline-characterisation-against-the-local-em-stitching-api)
in the design document.
