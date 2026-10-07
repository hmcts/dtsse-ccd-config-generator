# Document bundling

`sdk/document-bundling` (`com.github.hmcts:document-bundling`) builds a case bundle PDF inside
your service. You describe the bundle as a tree of sections and documents, the module fetches the
documents through a resolver you provide, converts them to PDF, and stitches them with a contents
page, cover sheets, bookmarks and page numbers. The rendering code is ported from
`em-stitching-api` (same PDFBox version) and its output is checked against goldens generated from
that service, so a default bundle looks the same as one from the shared service today.

## Getting started

```groovy
implementation 'com.github.hmcts:document-bundling:<version>'
```

```yaml
ccd:
  bundling:
    docmosis:                         # leave out if you have no office documents or cover pages
      convert-endpoint: ${DOCMOSIS_ENDPOINT}          # /rs/convert: office conversion
      render-endpoint: ${DOCMOSIS_RENDER_ENDPOINT}    # /rs/render: cover-page templates
      access-key: ${DOCMOSIS_ACCESS_KEY}
```

Then define one bean: a `DocumentResolver` that fetches your documents (see below). The
auto-configuration builds a `BundleRenderer` from every resolver bean, the Docmosis client when
configured, and any `BundlingExtension` beans. Outside Spring, build one yourself:

```java
BundleRenderer renderer = BundleRenderer.builder().resolver(myResolver).build();
```

## Rendering a bundle

```java
BundleRequest request = BundleRequest.builder()
    .externalId(UUID.randomUUID())
    .title("Hearing bundle")
    .fileName("case-1234-hearing-bundle.pdf")
    .root(BundleSection.builder("Case file")
        .section(BundleSection.builder("Applications")
            .document(BundleDocument.builder()
                .id("app-1").title("Application").date(LocalDate.of(2026, 1, 12))
                .reference(new DocumentReference("case-documents", "<document id>"))
                .build())
            .build())
        .section(BundleSection.builder("Correspondence")
            .emptySectionPolicy(EmptySectionPolicy.INCLUDE_PLACEHOLDER)
            .build())
        .build())
    .presentation(BundlePresentation.courtDefault().withPageNumbers(PageNumbers.BOTTOM_CENTRE_N_OF_M))
    .build();

try (BundleResult result = renderer.render(request, BundleExecutionContext.builder()
        .caseReference("1234567890123456").build())) {
    upload(result.artifact().open(), result.artifact().fileName());   // your CDAM upload
    result.documents();   // where each document ended up: media type, sha256, pages, start page
    result.warnings();    // things worth telling the user, such as an empty section
}
```

Sections nest as deep as you like and documents appear in the order you list them. A
`DocumentReference` is just a provider name plus the id your resolver understands. Each distinct
reference is fetched once per render, even if it appears in several places.

The result holds the finished PDF until you close it, so read or upload the file inside the
`try`. Where the PDF goes and how it is attached to the case is up to your service.
`test-projects/e2e` (`uk.gov.hmcts.divorce.bundling`) is the reference integration: a
decentralised event renders the bundle, uploads it to CDAM, attaches it to the case and records
it in the service's own case model.

If a document cannot be fetched or converted, the render fails with a `BundleGenerationException`
carrying a stable `code()`, the `stage()`, one `DocumentFailure` per document at fault, and a hint
on what to do. The message on its own says what failed, on which document, at which stage.
Nothing is left on disk.

To get a bundle anyway, set `.missingDocuments(MissingDocumentPolicy.PLACEHOLDER)` on the request.
A document that cannot be fetched, converted or opened is then replaced by a one-page placeholder
in its place, titled "<title> (missing)" in the contents and bookmarks, with a plain-English
reason printed on it. The result lists it in `missingDocuments()`: the document id, a
`MissingDocumentReason` (`NOT_FOUND`, `ACCESS_DENIED`, `UNAVAILABLE`, `UNSUPPORTED_FORMAT`,
`CONVERSION_FAILED`, `UNREADABLE`, `TOO_LARGE`) whose `message()` is safe to show users, the
technical `code` and `detail`, and the placeholder's start page. The outcome is
`COMPLETED_WITH_WARNINGS` with a `DOCUMENT_MISSING` warning per document. Request-level problems
(limits, an unregistered provider, an invalid request) still fail the render.

A source that is only temporarily unavailable (a timeout, a 5xx, a Docmosis outage) should not
end up as a placeholder if a retry would fetch it. `render(request, context, RenderAttempt.RETRYABLE)`
fails such a render instead; the durable worker passes `RETRYABLE` while the job has retries left
and `FINAL` on its last attempt. A plain `render(request, context)` is a final attempt.

## What you get by default

| Media type | Handling |
|---|---|
| `application/pdf` | Checked and passed through; its own bookmarks are kept under the document's bookmark |
| PNG, JPEG, TIFF, BMP, GIF | Placed on a page of the right size |
| Word, Excel, PowerPoint, RTF, plain text | Converted through the shared Docmosis service |
| audio (`audio/mpeg`, ...) and video (`video/mp4`, ...) | Never fetched: a generated link page built from the document's `MediaPlaceholder` (access URL, media type, duration, note) |
| anything else | The bundle fails, naming the type and the types that are supported |

The media type is detected from the content and wins over what the resolver declared: a
mislabelled document is converted as what it really is, with a `MEDIA_TYPE_MISMATCH` warning,
and content that flatly contradicts its label (a ZIP archive declared as PDF) fails with
`DOCUMENT_CONTENT_INVALID` naming both types. Each converted PDF is checked before assembly: an
encrypted, empty or corrupt one fails with `DOCUMENT_INSPECTION_FAILED`, and one with no
extractable text (scanned evidence) is bundled with a `NO_EXTRACTABLE_TEXT` warning, because
stitching cannot make it searchable. A source whose bookmark tree is circular or absurdly deep
is bundled with `OUTLINE_TRUNCATED`.

Presentation is chosen from presets: `BundlePresentation.courtDefault()` plus `withTableOfContents`,
`withSectionCoverSheets`, `withDocumentCoverSheets`, `withPageNumbers`, `withConfidentialMarking`
and `withWatermark`. Bookmarks follow the section tree. There is deliberately no way to put
free-form text or graphics over evidence pages.

Limits default to 100 documents, 300 MB per source, 1 GB output and 1,000 pages; change them per
field with `ccd.bundling.limits.*` or `.limits(...)`. `max-concurrent-renders` (default 2) caps how
many renders hold a PDFBox scratch buffer at once; extra renders wait their turn.

## Logs and metrics

Logs go through SLF4J with `externalId`, `stage` and `documentId` in the MDC (your own MDC values
are restored afterwards): one INFO line per stage, one WARN per warning, and exactly one ERROR
when a render fails, whose message is complete on its own. `result.timings()` has the time spent
in each stage. If a Micrometer `MeterRegistry` bean exists (or you call `.meterRegistry(...)` on
the builder) the renderer publishes `ccd.bundling.stage` timers and `ccd.bundling.documents`,
`pages`, `bytes`, `warnings{code}` and `failures{code}` counters.

## Asynchronous bundling (job outbox)

For bundles that should not be rendered inside a request, switch on the outbox with
`ccd.bundling.job.enabled=true`. `OutboxBundleJobService.submit` inserts one row into
`bundling.bundle_job` in your service's database using the caller's transaction, so the job exists
exactly when the event that triggered it commits. `externalId` is the idempotency key: submitting
the same id again returns the existing job.

```java
@Transactional
public void onHearingListed(BundleRequest request) {
  bundleJobService.submit(request, BundleExecutionContext.builder().caseReference(ref).build());
}
```

`BundleJobWorker` polls (your service needs `@EnableScheduling`), claims rows with
`SELECT ... FOR UPDATE SKIP LOCKED` under a lease, renders, and hands the open `BundleResult` to
your `BundleJobCompletionHandler` bean. The handler stores the PDF and returns a small summary
that is saved in the job's `result` column; the worker closes the result afterwards. The
`BundleJobContext` it receives carries the selector parameters and execution context the job was
submitted with, so the handler knows which case the bundle belongs to.

```java
@Bean
BundleJobCompletionHandler bundleCompletion(CaseDocumentClient cdam) {
  return (job, jobContext, request, result) -> {
    try (InputStream pdf = result.artifact().open()) {
      return Map.of("documentUrl", cdam.upload(request.fileName(), pdf).url());
    }
  };
}
```

Fetch and conversion failures are retried with backoff and the history is kept on the job;
everything else, including an exception from your handler (`COMPLETION_FAILED`), fails the job
on the spot. A worker that outlives its lease cannot overwrite the outcome recorded by the worker
that took over. A `BundleDocumentSelector` bean lets you pick the documents at run time instead of
at submit time, and `BundleProgressListener` beans receive state changes. The table
`bundling.bundle_job` is created by the SDK's standard library migration (`SdkFlywayMigration`,
run by the decentralised runtime's Flyway strategy before the application's own migrations).

### A bundle that is always current

To regenerate a bundle whenever its inputs change (say, every time a document is added to the
case), submit selector parameters under a coalesce key from the transaction that made the change,
and register a `BundleDocumentSelector` that compiles the request when the job runs:

```java
bundleJobService.submitCoalesced("case-bundle:" + caseId, Map.of("caseId", caseId.toString()),
    BundleExecutionContext.builder().caseReference(ref).build());
```

Submissions with the same key collapse onto the job that is still waiting for its first claim.
Ten documents uploaded in one event, or in ten quick events, give one render, and the job's
`coalescedSubmissions` counts what it absorbed. Joining a waiting job is an upsert that locks its
row until the submitting transaction commits, and the worker's `SKIP LOCKED` claim passes over a
locked row, so a joined job is never claimed before the change that joined it is visible to its
selector. Once a job is claimed it stops absorbing submissions: a change made mid-render queues one
follow-up. Two concurrent transactions submitting the same key end up on the same job, the second
waiting on the first's row. Because joining takes a row lock, a transaction submitting several keys
should submit them in a consistent order, or two such transactions can deadlock.

The waiting job keeps the selector parameters and execution context it was first submitted with,
so make the parameters a function of the key; a submission whose parameters differ is logged and
its parameters ignored. Keys are at most 255 characters. Submit under READ COMMITTED: under
REPEATABLE READ or SERIALIZABLE a racing coalesced submission can fail with a serialization error
(SQLSTATE 40001) that the caller must retry.

Renders for one key can still overlap (an in-flight job and its follow-up on different pods), and
a retried job can finish after a newer one. Among completed jobs, the one with the greatest
`BundleJob.claimedAt()` reflects the newest state: its selector ran after that claim, so it saw
every change submitted before it. Break ties by `externalId`, and fall back to `submittedAt` for a
job claimed before `claimed_at` existed. `findLatest(key)` returns the job that reflects the newest
state, for a "regenerating..." status: the waiting job if there is one, otherwise the most recently
claimed. The completion handler receives the job's `BundleJobContext` (selector parameters and
execution context), so it knows which case the bundle is for. Rows are never deleted by the
module; an always-current bundle adds one per burst of changes, so purge old terminal rows on your
own schedule if the table grows.

When a job completes, the worker records a `BundleJobReport` with it, from the same render the
handler stored: the request that was rendered (for a selector-driven job, the documents it
selected), where each document landed, the missing documents and why, the warnings, and the PDF's
file name, size, checksum and page count. `findReport(externalId)` returns it, so a service can
keep just the job id beside the stored PDF and describe the bundle from the job: "pages 40 to 52",
"two documents are missing", without a table of its own. Rows that back a stored bundle are then
the bundle's record, so purge only those no stored bundle points at.

A coalesced bundle completed with placeholders for `UNAVAILABLE` documents is re-run later, so it
fills in once the source is back: the worker queues a job under the same key that is not claimable
for `requeue.delay`. A change to the case in the meantime joins it and makes it immediate. A bundle
is re-run at most `requeue.max-attempts` times in a row for unavailable documents (counted in the
execution context attribute `bundling.unavailableRequeues`).

pcs-api's `CaseBundleTrigger` is a worked example: a Hibernate listener that submits from the
before-commit hook, so every path that adds, amends or removes a document is covered.

Properties under `ccd.bundling.job.*`: `enabled` (default `false`), `worker.enabled` (`true`), `worker.poll-delay` (`1s`), `worker.batch-size` (`5`),
`worker.max-concurrent-renders` (`2`), `worker.lease-duration` (`5m`), `retry.max-attempts` (`3`),
`retry.initial-delay` (`5s`), `retry.multiplier` (`2.0`), `retry.max-delay` (`5m`),
`requeue.enabled` (`true`), `requeue.delay` (`15m`), `requeue.max-attempts` (`3`).

## Extending it

### Fetching documents: `DocumentResolver`

A resolver turns the references in a request into content. It is called once per render with
every reference for its provider, so it can batch lookups or authorisation checks. Return a
`ResolvedDocument` for each one you found and a `ResolutionFailure` with a typed reason for each
you did not; the module reports the failures together.

```java
@Component
public class CaseDocumentResolver implements DocumentResolver {

  @Override
  public String provider() {
    return "case-documents";
  }

  @Override
  public ResolvedDocuments resolveAll(List<DocumentReference> references, BundleExecutionContext context) {
    Map<DocumentReference, ResolvedDocument> found = new LinkedHashMap<>();
    Map<DocumentReference, ResolutionFailure> missing = new LinkedHashMap<>();
    for (DocumentReference reference : references) {
      StoredFile file = store.find(reference.id());
      if (file == null) {
        missing.put(reference, new ResolutionFailure(ResolutionFailureReason.NOT_FOUND, "No such document"));
      } else {
        found.put(reference, new StreamDocument(file.open(), file.mediaType(), file.name(), file.size()));
      }
    }
    return new ResolvedDocuments(found, missing);
  }
}
```

`StreamDocument` is whatever small class you write to implement `ResolvedDocument` (an input
stream, media type, file name, optional length and checksum, and `close()`). Several resolvers can
coexist, one per provider name, so a bundle can mix documents from CDAM and from a local store.

### Handling more file types: `BundlingExtension`

Each media type maps to a `DocumentHandler` that turns a fetched document into a PDF. An extension
adds, replaces or removes handlers. Register it as a bean (or `.extension(...)` on the builder).

```java
@Component
public class OutlookMessages implements BundlingExtension {

  @Override
  public String name() {
    return "outlook-messages";
  }

  @Override
  public void configure(BundlingExtensionContext context) {
    context.addHandler("application/vnd.ms-outlook", new MsgHandler());
  }

  static final class MsgHandler implements DocumentHandler {
    @Override
    public HandledDocument handle(ResolvedDocument source, HandlerContext context) throws DocumentHandlingException {
      Path pdf;
      try {
        pdf = context.createTempFile(".pdf");
        MsgToPdf.convert(source.content(), pdf);          // your conversion
      } catch (IOException e) {
        throw new DocumentHandlingException("Could not convert the message", e);
      }
      return HandledDocument.of(pdf);
    }
  }
}
```

`addHandler` refuses a type that already has a handler and `replaceHandler` refuses one that does
not, so you cannot override a built-in by accident; use `replaceHandler` to swap the image handler
for one that adds a caption, say, and `removeHandler` to forbid a type. `HandlerContext` gives a
handler temp files inside the render's directory (cleaned up with the render), the Docmosis client
when configured, and the `BundleDocument` being handled. The PDF a handler returns must live in that
directory. Several extensions apply in bean order; the last registration for a type wins.

### Cover pages and watermarks

The contents page, section and document cover sheets and the title page are generated by the
module from your titles and dates. A cover page is a Docmosis template rendered with data from
the request and placed first, before the title and contents pages, bookmarked "Cover Page" and
left unnumbered, as the microservice does. It needs `ccd.bundling.docmosis.render-endpoint`; a
request with a cover page but no render endpoint fails at `VALIDATE` with
`DOCMOSIS_NOT_CONFIGURED`, and a template Docmosis cannot render fails at `CONVERT` with
`COVER_PAGE_FAILED`.

```java
BundleRequest.builder()
    .coverPage(new CoverPage("FL-FRM-GOR-ENG-12345.docx", Map.of("caseReference", caseRef)))
```

A watermark is an image you register on the renderer by name and refer to from the presentation.
It is centred on the first or every page of each source document (never on generated pages),
over or behind the content:

```java
BundleRenderer.builder().resolver(resolver).watermarkImage("hmcts-logo", Path.of("/opt/app/hmcts.png"))
BundlePresentation.courtDefault().withWatermark(
    new WatermarkPreset("hmcts-logo", WatermarkPreset.Scope.ALL_PAGES, WatermarkPreset.Rendering.TRANSLUCENT))
```

### Storing the result

The module hands back a file; storing it is a port in the loosest sense, you just call your own
code. The e2e `BundlePublisher` uploads to CDAM, attaches the document to the case and builds the
service's `CaseBundle` from `result.documents()`.

## Telemetry

Logs go through SLF4J with the MDC keys `externalId`, `stage` and `documentId` (restored to the
caller's values afterwards): one INFO per stage, one WARN per warning, exactly one ERROR at final
failure whose message is complete on its own. `result.timings()` carries the wall-clock time per
stage. When a Micrometer `MeterRegistry` bean exists (or `.meterRegistry(...)` is called on the
builder) the renderer publishes `ccd.bundling.stage` timers and `ccd.bundling.documents`,
`pages`, `bytes`, `warnings{code}` and `failures{code}` counters; tag values are bounded.

## Testing

The module's tests are behavioural: a real PDF layer, an in-memory resolver, a stub Docmosis on a
local HTTP server, and a characterisation suite that renders 17 scenarios and compares them field
by field with goldens generated from `em-stitching-api` (`src/test/resources/characterisation`; the
few deliberate differences are listed on `CharacterisationRegressionTest`). The e2e project proves
the full stack: a decentralised event rendering through the embedded CCD and CDAM, and a failing
bundle surfacing its error with nothing uploaded. `BundleRenderer` is an interface, so unit tests of
your event handlers can fake it.

## What comes next

[document-bundling-scope.md](document-bundling-scope.md) has the design goals, the delivery plan,
the feature matrix and the one-click bundle requirements coverage. Every rendering feature the
microservice offers is now in the module; built-in CDAM adapters and the items in the scope
document's section 4 come later.
