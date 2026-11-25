# Doc toolkit

`sdk/doc-toolkit` (`com.github.hmcts:doc-toolkit`) makes PDFs from case documents inside your
service:

- **bundles**: documents in titled sections, with a title page, contents, cover sheets, bookmarks
  and page numbers;
- **joins**: one or more documents put together in order;
- **print-ready letters** for bulk print: PDF/X-1a, capped resolution and a blank margin.

It fetches documents from CDAM and stores what it makes there, as your service's system user. A
document can be made on the spot, queued once, or **kept up to date with its case**: the toolkit
rebuilds it after every case event and renders it when it changed.

## Getting started

```groovy
implementation 'com.github.hmcts:doc-toolkit'
```

```yaml
ccd:
  doc-toolkit:
    docmosis:                                  # leave out if you only use PDFs, images and HTML
      convert-endpoint: ${DOCMOSIS_ENDPOINT}   # /rs/convert
      access-key: ${DOCMOSIS_ACCESS_KEY}
    temp-directory: /tmp/docs                  # optional; defaults to java.io.tmpdir
    jobs:
      enabled: true                            # background rendering and kept-up-to-date documents
      worker:
        enabled: true                          # false on instances that only queue
        max-concurrent-renders: 2
```

Give the toolkit the user to act as in CDAM:

```java
@Bean
DocToolkitSystemUser docToolkitSystemUser(IdamService idam) {
  return () -> idam.retrieveSystemUpdateUserDetails().getAuthToken();
}
```

## Requests

There is one request type, `PdfRequest`. With documents only it is a plain join; every bundle
feature is switched on by name.

```java
// A bundle
PdfRequest.builder("Case bundle")
    .fileName(caseReference + "-case-bundle.pdf")
    .section(PdfSection.builder("Statements of case")
        .documents(parts)
        .placeholderWhenEmpty()                  // a page saying the section is empty
        .build())
    .section(PdfSection.builder("Applications")
        .documents(looseParts)
        .section(PdfSection.builder("GA1: Adjourn").documents(applicationParts).build())
        .build())
    .titlePage()
    .tableOfContents()
    .sectionCoverSheets()
    .pageNumbers(PageNumbers.BOTTOM_CENTRE_N_OF_M)
    .confidentialMarking()
    .missingDocuments(MissingDocuments.PLACEHOLDER)
    .category("bundles")                         // put on the stored document
    .build();

// A letter for bulk print
PdfRequest.builder("CPC-01 1234")
    .document(PdfPart.builder(DocSource.bytes(coversheet, "coversheet.pdf")).unchanged().build())
    .document(claimForm)                         // a CCD Document
    .print(PrintOptions.bulkPrint())
    .build();
```

| Option | Default |
|---|---|
| `fileName` | the title plus `.pdf` |
| `coverPage(DocSource)` | none |
| `titlePage()`, `tableOfContents()`, `sectionCoverSheets()`, `documentCoverSheets()` | off |
| `pageNumbers(...)` | `NONE` |
| `confidentialMarking()` | off; marks the pages of parts built with `confidential()` |
| `watermark(...)` | none; on parts' pages, not on generated pages |
| `missingDocuments(...)` | `FAIL` |
| `print(PrintOptions)` | none |
| `category(...)` | none |
| `classification(...)` | `PUBLIC` |

Top-level documents come before sections, and sections nest. `build()` rejects a request that
would produce nothing, `unchanged()` parts anywhere but a printed plain join, and a printed plain
join with placeholders (a letter is sent complete or not at all).

### Parts

```java
PdfPart.builder(DocSource.of(document))
    .id(entity.getId().toString())   // echoed in failures
    .title("Claim form")             // contents and bookmarks; default: the source's name
    .date(issueDate)                 // shown in the contents
    .confidential()
    .build();
```

`PdfSection.document(Document)` and `.document(DocSource)` add a part with defaults.

### Sources

| Source | Use |
|---|---|
| `DocSource.of(Document)` | A CCD `Document` field, fetched from CDAM |
| `DocSource.cdam(UUID)` | A CDAM document id the service stores itself |
| `DocSource.bytes(byte[], name)` | Content the service has |
| `DocSource.file(Path)` | A local file; not for queued jobs |
| `HtmlPage` | A page the service writes, for anything the toolkit cannot convert |

PDFs pass through after checks; JPG, PNG, BMP, GIF and TIFF are decoded in-process; Word, Excel,
PowerPoint, TXT, RTF and CSV go through Docmosis; HTML pages are rendered in-process with nothing
fetched (images must be `data:` URIs, Latin script only, escape user text).

### Documents that cannot be included

With `MissingDocuments.FAIL` rendering throws `DOCUMENTS_FAILED`. With `PLACEHOLDER` a page
saying why takes the document's place, and the result lists it:

```java
record DocFailure(Optional<String> id, String title, List<String> sectionPath, OptionalInt page,
    Reason reason, String detail)
```

`sectionPath` starts with the request's title; `page` is where the placeholder is. `reason` is
one of `NOT_FOUND`, `ACCESS_DENIED`, `UNAVAILABLE` (may pass), `UNSUPPORTED_FORMAT`,
`CONVERSION_FAILED`, `UNREADABLE`, `TOO_LARGE`; `reason().message()` is fit to show, `detail` is
for logs.

### Bulk print

`PrintOptions.bulkPrint()` is A4, PDF/X-1a:2001, CMYK, 300 dpi, a 20 mm margin, landscape pages
turned, even page counts, a 20 MiB limit and greyscale above 80% of it; change any of it with
`toBuilder()`. A printed plain join prints each part on its own sheets, `unchanged()` parts as they
are; a printed bundle is assembled, then printed whole.

## Making a document now

```java
try (PdfResult result = docToolkit.render(request)) {      // a temporary file; close deletes it
  sendLetter(result.open());
}

UploadedDoc stored = docToolkit.upload(request, caseReference);  // in CDAM, attached to the case
caseData.setOrderPdf(stored.document());

docToolkit.delete(document);                                     // when the service prunes
```

Both run on the calling thread. Errors are `DocToolkitException` with `reason()`
(`INVALID_REQUEST`, `DOCUMENTS_FAILED`, `NOT_CONFIGURED`, `OUTPUT_TOO_LARGE`, `ASSEMBLY_FAILED`,
`UPLOAD_FAILED`, `IO_FAILURE`), `isTransient()` and `failures()`.

## Background rendering

With `ccd.doc-toolkit.jobs.enabled`, a worker renders queued jobs, stores each in CDAM and hands it
to your service's one `DocJobHandler`:

```java
@Component
class Documents implements DocJobHandler {
  public void completed(DocJob job, UploadedDoc uploaded) { ... }  // may be called again: idempotent
  public void failed(DocJob job) { ... }                          // job.failure() says why
  public void removed(DocJob job) { ... }                         // a kept document no longer applies
}
```

The handler runs in one transaction with the worker's own bookkeeping, through the application's
transaction manager. If the worker no longer holds the job (its lease expired, or a newer revision
arrived), the transaction rolls back, the handler's writes with it, and the upload is deleted.

### One-off documents

```java
docJobs.submit(request, caseReference);   // queued in the caller's transaction
```

A one-off job's row is deleted once its handler has it.

### Documents kept up to date

Declare the document; case events do the rest:

```java
@Component
class CaseBundle implements CaseDocDefinition {
  public String key() { return "case-bundle"; }
  public Set<String> caseTypes() { return Set.of("PCS"); }
  public Optional<PdfRequest> build(long caseReference) { ... }   // empty: the case has none
}
```

After every case event of those case types, including system events, the toolkit calls `build`
inside the event's transaction and compares the request's SHA-256 with the last one queued. A
changed request is queued at the event's case revision; an unchanged one writes nothing. The
service never detects changes, so whatever `build` reads (visibility, folders, titles) is covered.
Case events are the only trigger: a change made outside an event is not seen.

`build` must be read-only, deterministic and local (no remote calls). If it throws, the event
fails. Its request may hold up to 1 MiB inline; keep documents in CDAM.

`docJobs.status(key, caseReference)` says where a document is: `QUEUED` or `IN_PROGRESS` while a
newer version is on its way, `DONE`, or `FAILED`. Combine it with what the handler stored to show
"preparing", "updating" or "failed".

Behaviour:

- One row per case and key. A newer revision replaces a queued request; a burst of events leaves
  at most one render running and one queued. Renders for a key do not overlap while leases hold.
- The handler gets `job.revision()`; keep each version. The toolkit never deletes a document it
  handed over: a reader may have an older one open, and CDAM serves a document only while the case
  data refers to it.
- Three attempts, 30 seconds then a minute apart, for failures that may pass. A document only
  temporarily unavailable becomes a placeholder on the last attempt, and the render is re-run 15
  minutes later, up to three times.
- Attempts are counted when a job is claimed, so a render that kills its worker counts; a job
  whose every worker died fails as `CRASHED`. Leases are five minutes, renewed while rendering.
- A change to the toolkit's rendering bumps `DocRequestJson.RENDERING_VERSION`, which changes
  every request, so each case re-renders on its next event.

### The case event hook

Kept-up-to-date documents use `CaseEventHook` from `decentralised-runtime`, which any library can
implement. Hooks run in every locally saved event, after the case row is written (with its new
revision) and before the audit snapshot, in the event's transaction; a hook that throws fails the
event. Hooks read the case and write only their own tables.

## Storage

Jobs live in `bundling.doc_job`, created by the SDK's library migrations (location
`bundling-db`). Earlier migrations created document-bundling's tables; `V0005` drops them.

## Local runs and tests

`ccd-sdk-test-support` has a Docmosis stub, `DocmosisStubController`, which answers
`/rs/convert` with a one-page PDF naming the file. Turn it on with
`ccd.doc-toolkit.docmosis-stub.enabled=true`.

The e2e project has worked examples: `CaseworkerCreateBundle` renders and uploads a bundle in an
event, and `CaseNotesDocument` keeps a case's notes as a PDF up to date. The cftlib suite checks
both through CCD and CDAM.
