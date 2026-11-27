# Doc toolkit

`sdk/doc-toolkit` (`com.github.hmcts:doc-toolkit`) makes PDFs from case documents inside your
service:

- **bundles**: documents in titled sections, with a title page, contents, cover sheets, bookmarks
  and page numbers;
- **joins**: one or more documents put together in order;
- **print-ready letters** for bulk print: PDF/X-1a, capped resolution and a blank margin.

It fetches documents from CDAM as your service's system user. A document is either **rendered**
on the spot, to a temporary PDF (bulk print), or **submitted**: rendered in the background, stored
in CDAM on the case and handed back to your service. Submitting after every case event keeps a
document up to date.

## Getting started

```groovy
implementation 'com.github.hmcts:doc-toolkit'
```

```yaml
ccd:
  doc-toolkit:
    system-user:                               # the IDAM user CDAM documents are fetched and stored as
      username: ${IDAM_SYSTEM_USERNAME}
      password: ${IDAM_SYSTEM_PASSWORD}
    docmosis:                                  # leave out if you only use PDFs, images and HTML
      convert-endpoint: ${DOCMOSIS_ENDPOINT}   # /rs/convert
      access-key: ${DOCMOSIS_ACCESS_KEY}
    temp-directory: /tmp/docs                  # optional; defaults to java.io.tmpdir
    jobs:
      enabled: true                            # background rendering
      worker:
        enabled: true                          # false on instances that only queue
        max-concurrent-renders: 2
```

The toolkit signs the system user in through the service's `IdamClient` and reuses the token for
half an hour. CDAM also needs the service's `CaseDocumentClientApi` and `AuthTokenGenerator`
beans; without them, or without `system-user`, CDAM documents fail as `NOT_CONFIGURED`.

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
join with placeholders (a letter is sent complete or not at all) or the confidential marking (it
prints each part as it is; a printed bundle carries the marking).

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
one of `NOT_FOUND`, `ACCESS_DENIED`, `UNSUPPORTED_FORMAT`, `CONVERSION_FAILED`, `UNREADABLE`,
`TOO_LARGE`; `reason().message()` is fit to show, `detail` is for logs.

Placeholders are for documents that will not come back. A document only temporarily
`UNAVAILABLE` (CDAM or Docmosis down, timing out or throttling) fails the render under either
policy, with `isTransient()` true, so it can be tried again rather than published with a gap.

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
```

It runs on the calling thread, so use it where the caller is already in the background, as bulk
print is. A document that belongs on a case is [rendered in the background](#rendering-in-the-background)
instead, so a case event never waits on rendering, Docmosis or CDAM. Errors are `DocToolkitException` with `reason()`
(`INVALID_REQUEST`, `DOCUMENTS_FAILED`, `NOT_CONFIGURED`, `OUTPUT_TOO_LARGE`, `ASSEMBLY_FAILED`,
`UPLOAD_FAILED`, `IO_FAILURE`), `isTransient()` and `failures()`.

## Rendering in the background

With `ccd.doc-toolkit.jobs.enabled`, inject `DocJobs` and submit a request for a case:

```java
docJobs.submit(caseReference, "case-bundle", request);
```

A case has one document per key. A submitted request is compared, by fingerprint, with the one last
handed to the service: the same request does nothing (and cancels any newer one still pending),
and a different one is queued, replacing any pending request. A request that could not be made is
never recorded as handed over, so submitting it again retries it. So the usual way to keep a
document up to date is to submit it after every case event, from a `CaseEventHook`, without
working out what changed:

```java
@Component
class CaseBundleHook implements CaseEventHook {
  public void afterCaseEvent(CaseEventContext event) {
    docJobs.submit(event.caseReference(), "case-bundle", bundle.build(event.caseReference()));
  }
}
```

A document made once, such as an order, is submitted under a key of its own:

```java
docJobs.submit(caseReference, "order-" + order.getId(), orderRequest);
```

It is never superseded, and submitting it again does nothing; the handler tells documents apart
by `job.key()`.

Submit from a `CaseEventHook`, after the event writes the case row and before it commits.
The case lock orders submissions, and rolling back the event also rolls back the queued job.
The toolkit records the event's case revision and hands it back as
`job.caseRevision()`: the document shows the case as it was at that revision, and stays accurate
until a later event changes the request. It only increases, so it orders a document's versions.

Building the request must be cheap and local (no remote calls), as it runs in the event. A
request may hold up to 10 MiB inline, room for a document the service generates itself; documents
the case already has are referred to by CDAM id.

A worker renders each queued request, stores the PDF in CDAM unattached and hands it to your
service's one `DocJobHandler`:

```java
@Component
class Bundles implements DocJobHandler {
  public void completed(DocJob job, UploadedDoc uploaded) {
    versions.append(job.caseReference(), job.key(), job.caseRevision(), uploaded);
  }

  // Optional: a document that cannot be made. Does nothing by default.
  public void failed(DocJob job, DocJobFailure failure) {
    orders.markFailed(job.key(), failure.reason());
  }
}
```

In one transaction, through the application's transaction manager, the worker takes the case
lock (the `for update` on `ccd.case_data` every case event takes first), takes the job off the
queue, records what the service is given, calls the handler and attaches the PDF to the case.
Holding the case lock means an event on that case waits for the handler to commit, and the handler
may write rows referencing the case without deadlocking with an event. Taking the job off the queue
is guarded by the case revision and request rendered and the worker's lease token, so a render
superseded by a newer submit never reaches the handler, and versions arrive in the order they were
submitted. If the handler or the attach fails, the database transaction rolls back. If CDAM
attached the PDF but its response was lost, the PDF remains attached and a retry can attach a second copy.

When the toolkit gives up on a document (it failed permanently, every attempt failed, or every
worker died), it calls `failed` the same way: once, in one transaction holding the case lock, and
only for the request still current. A one-off document uses it to show that it could not be made;
a document kept up to date can ignore it and keep its last version. If `failed` throws, the job is
failed without it and the error logged; if giving up fails for any other reason, such as waiting
too long for the case lock, the job is left for another worker once its lease expires.

The toolkit never deletes a document. What the handler is given is the service's: keep each
version, since a reader may have an older one open, refer to them from case data, since CDAM
serves a document only then, and prune through CDAM if you want to. Unattached uploads expire
under CDAM's time to live.

Behaviour:

- At most one pending request per case and key; a burst of submits leaves one render running and
  one queued. Renders for a key do not overlap while leases hold.
- Three attempts, 30 seconds then a minute apart, for failures that may pass: a document only
  temporarily unavailable, a failed upload or attach, or a failing handler. After the last, the
  handler's `failed` is called, the service keeps the version it had, and the next submit tries
  again.
- Attempts are counted when a job is claimed, so a render that kills its worker counts; a job
  whose every worker died fails as `CRASHED`. Leases are ten minutes; a render that takes longer
  is claimed again by another worker, and the slow one's result is refused.
- A change to the toolkit's rendering bumps `DocRequestJson.RENDERING_VERSION`, which changes
  every request, so each document re-renders on its next submit.

## Storage

Two tables, created by the SDK's library migrations (location `bundling-db`) after the runtime's
`ccd` schema: `bundling.doc_job` holds work not yet done, a row deleted once its document is handed
over or given up on, and `bundling.doc_rendered` the fingerprint last handed to the service for
each case and key. Rows reference their case in `ccd.case_data` and are deleted with it. Requests
are stored as `jsonb`. If a worker cannot read a queued request, it calls `failed` with
`UNREADABLE_REQUEST`. A case event's hooks run after its case row is written, so a document can
be submitted in the event that creates its case. Earlier migrations created document-bundling's tables; `V0005` drops them.

## Local runs and tests

`ccd-sdk-test-support` has a Docmosis stub, `DocmosisStubController`, which answers
`/rs/convert` with a one-page PDF naming the file. Turn it on with
`ccd.doc-toolkit.docmosis-stub.enabled=true`.

The e2e project has worked examples: `CaseworkerCreateBundle` renders and uploads a bundle in an
event, and `CaseNotesDocument` keeps a case's notes as a PDF up to date. The cftlib suite checks
both through CCD and CDAM.
