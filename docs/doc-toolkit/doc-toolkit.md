# Doc toolkit

`sdk/doc-toolkit` (`com.github.hmcts:doc-toolkit`) makes PDFs from case documents inside your
service:

- **bundles**: documents in titled sections, with a title page, contents, cover sheets, bookmarks
  and page numbers;
- **joins**: one or more documents put together in order;
- **print-ready letters** for bulk print: PDF/X-1a, capped resolution and a blank margin.

It fetches documents from CDAM and stores what it makes there, as your service's system user. A
document is either made on the spot, or **submitted** to render in the background, each version
handed back to your service; submitting after every case event keeps a document up to date.

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
      enabled: true                            # background rendering
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

UploadedDoc stored = docToolkit.upload(request, caseReference);  // in CDAM, attached to the case
caseData.setOrderPdf(stored.document());
```

Both run on the calling thread. Errors are `DocToolkitException` with `reason()`
(`INVALID_REQUEST`, `DOCUMENTS_FAILED`, `NOT_CONFIGURED`, `OUTPUT_TOO_LARGE`, `ASSEMBLY_FAILED`,
`UPLOAD_FAILED`, `IO_FAILURE`), `isTransient()` and `failures()`.

## Rendering in the background

With `ccd.doc-toolkit.jobs.enabled`, inject `DocJobs` and submit a request for a case:

```java
docJobs.submit(caseReference, "case-bundle", request);
```

A case has one job per key. A submitted request is compared, by SHA-256, with the last one: a
changed request replaces it, and an unchanged one writes nothing unless the last render failed. So the usual way to keep a
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

Submit inside the case event's transaction, building the request from the case there: the case
lock puts submissions in the order the case changed, and nothing is queued if the event rolls
back. Building the request must be cheap and local (no remote calls), as it runs in the event. A
request may hold up to 1 MiB inline; keep documents in CDAM.

A worker renders each queued request, stores the PDF in CDAM unattached and hands it to your
service's one `DocJobHandler`:

```java
@Component
class Bundles implements DocJobHandler {
  public void completed(DocJob job, UploadedDoc uploaded) {
    versions.append(job.caseReference(), job.key(), uploaded);
  }
}
```

In one transaction, through the application's transaction manager, the worker finishes the job,
calls the handler and attaches the PDF to the case. Finishing first means a render superseded by
a newer submit never reaches the handler, so versions arrive in the order they were submitted;
once finished, the job's row stays locked, so a submit for that case waits for the handler to
commit. If the handler or the attach fails, everything rolls back and the job is rendered again.

The toolkit never deletes a document. What the handler is given is the service's: keep each
version, since a reader may have an older one open, refer to them from case data, since CDAM
serves a document only then, and prune through CDAM if you want to. A render nobody is given is
never attached, so CDAM's time to live has dm-store delete it.

Behaviour:

- One row per case and key, deleted with the case. A burst of submits leaves at most one render
  running and one queued. Renders for a key do not overlap while leases hold.
- Three attempts, 30 seconds then a minute apart, for failures that may pass: a document only
  temporarily unavailable, a failed upload or attach, or a failing handler. After the last, the
  failure is logged, the service keeps the version it had, and the next submit tries again.
- Attempts are counted when a job is claimed, so a render that kills its worker counts; a job
  whose every worker died fails as `CRASHED`. Leases are five minutes, renewed while rendering.
- A change to the toolkit's rendering bumps `DocRequestJson.RENDERING_VERSION`, which changes
  every request, so each document re-renders on its next submit.

## Storage

Jobs live in `bundling.doc_job`, created by the SDK's library migrations (location
`bundling-db`) after the runtime's `ccd` schema. Each row references its case in `ccd.case_data`
and is deleted with it; the check is deferred to commit, so a document can be submitted in the
event that creates its case. Earlier migrations created document-bundling's tables; `V0005` drops them.

## Local runs and tests

`ccd-sdk-test-support` has a Docmosis stub, `DocmosisStubController`, which answers
`/rs/convert` with a one-page PDF naming the file. Turn it on with
`ccd.doc-toolkit.docmosis-stub.enabled=true`.

The e2e project has worked examples: `CaseworkerCreateBundle` renders and uploads a bundle in an
event, and `CaseNotesDocument` keeps a case's notes as a PDF up to date. The cftlib suite checks
both through CCD and CDAM.
