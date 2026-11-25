# Doc Toolkit

`sdk/doc-toolkit` (`com.github.hmcts:doc-toolkit`) turns case documents into PDFs inside your
service:

- **bundles**: documents in titled sections, with a contents page, bookmarks and page numbers;
- **conversions**: one or more documents joined into a single PDF;
- **print-ready output** for bulk print (Xerox): PDF/X-1a, capped image resolution and a blank
  margin, on a bundle or a conversion.

It fetches documents from CDAM and stores the result there, as your service's system user.

## Getting started

```groovy
implementation 'com.github.hmcts:doc-toolkit:<version>'
```

```yaml
ccd:
  doc-toolkit:
    docmosis:                                  # leave out if you only use PDFs, images and HTML
      convert-endpoint: ${DOCMOSIS_ENDPOINT}   # /rs/convert
      access-key: ${DOCMOSIS_ACCESS_KEY}
    temp-directory: /tmp/docs                  # optional; defaults to java.io.tmpdir
```

Besides `ccd.doc-toolkit.jobs.*`, which turns on [background rendering](#rendering-in-the-background),
those are the only properties. Everything about the output is set on each request.

The toolkit uses your application's `CaseDocumentClientApi` (from
`ccd-case-document-am-client`) and `AuthTokenGenerator`, which a CCD service already has. Give it
the user to act as:

```java
@Bean
DocToolkitSystemUser docToolkitSystemUser(IdamService idam) {
  return () -> idam.retrieveSystemUpdateUserDetails().getAuthToken();
}
```

Then inject the `DocToolkit`:

```java
UploadedDoc bundle = docToolkit.renderAndUpload(
    BundleRequest.builder("Hearing bundle")
        .section(BundleSection.builder("Applications")
            .document(caseData.getApplication())          // a CCD Document field
            .build())
        .section(BundleSection.builder("Evidence")
            .documents(evidence)
            .build())
        .build(),
    caseId, eventId);

caseData.setHearingBundle(bundle.document());
```

`renderAndUpload` stores the PDF in CDAM and attaches it to the case, and returns the CCD
`Document` to put in a case field. `render(request)` returns the PDF as a temporary file instead;
close the result to delete it.

`render` and `renderAndUpload` are synchronous and run on the calling thread. A large bundle can
take seconds and hundreds of megabytes of PDFBox scratch space (memory capped at 64 MB, the rest
spilled to the temp directory). To keep that off the threads answering CCD callbacks, queue it
instead: see [Rendering in the background](#rendering-in-the-background).

Outside Spring, build the toolkit yourself:

```java
DocToolkit toolkit = DocToolkit.builder()
    .docmosis(new HttpDocmosisRenderService(
        DocmosisConnection.withDefaults(convertEndpoint, accessKey), tempDir))
    .cdam(caseDocumentClient, authTokenGenerator, systemUser, caseLocator)
    .tempDirectory(tempDir)
    .build();
```

## Documents

A document comes from a `DocSource`:

| Source | Use |
|---|---|
| `DocSource.of(Document)` | A CCD `Document` field's value. The content is fetched from CDAM. |
| `DocSource.cdam(UUID)` | A CDAM document, by id, for services that store the id themselves. |
| `DocSource.file(Path)` / `bytes(byte[], name)` | Documents the service has itself, and tests. |
| `HtmlPage` | A page the service writes, for anything the toolkit cannot convert. |

Bundle sections take a CCD `Document` directly and list it under its file name. Use
`BundleDocument.builder(source)` to give it a title, a date for the contents page, or the
confidential marking.

### File types

| Upload | Converted by |
|---|---|
| PDF | Checked (unencrypted, parseable, at least one page) and passed through. Its bookmarks are kept under the document's bookmark. |
| JPG/JPEG, PNG, BMP, GIF | Decoded in-process. PNG transparency prints white. For print, a JPEG's EXIF orientation is applied, so phone photos print upright. |
| TIF/TIFF | Decoded in-process, one page per frame. Thumbnails and masks stored alongside a page are skipped. |
| Word, Excel, PowerPoint, TXT, RTF, CSV | Docmosis. |
| HTML (`HtmlPage`) | Rendered in-process. |

The declared media type is checked against the content. Content that is clearly another type is
converted as that type. A missing or `application/octet-stream` type is inferred from the file
name. Binary content declared or named as text, RTF or CSV is refused rather than sent to
Docmosis, which would print it as garbage.

### Anything else: HTML pages

There is no plug-in for more file types. For a document the toolkit cannot convert, put a page
in its place that says what it is:

```java
DocSource source = SUPPORTED.contains(document.getContentType())
    ? DocSource.of(document.getFile())
    : HtmlPage.builder()
        .title(document.getName())
        .body("<h1>" + escape(document.getName()) + "</h1>"
            + "<p>Audio recording, 42 minutes, held by the court.</p>")
        .build();
```

The body can be a fragment or a whole HTML document, with `css(...)` for styling. The page is A4
with a 20 mm margin. Nothing is fetched while rendering: images must be `data:` URIs, and links
to stylesheets, fonts or images elsewhere are ignored, so a page cannot reach the network or the
file system. Text is set in the PDF standard fonts, which cover Latin script only. Escape any
text that comes from users.

## Bundles

```java
BundleRequest.builder("Hearing bundle")
    .fileName(caseRef + "-hearing-bundle.pdf")          // default: the title plus .pdf
    .coverPage(DocSource.of(frontSheet))                 // before the contents page
    .section(BundleSection.builder("Correspondence")
        .documents(letters)
        .placeholderWhenEmpty()                          // a page saying the section is empty
        .build())
    .tableOfContents(true)                               // default true
    .sectionCoverSheets(true)                            // default true
    .documentCoverSheets(false)                          // default false
    .pageNumbers(PageNumbers.BOTTOM_CENTRE_N_OF_M)       // the default
    .confidentialMarking(true)                           // default true
    .watermark(Watermark.image(draftPng))
    .missingDocuments(MissingDocuments.PLACEHOLDER)      // the default
    .print(PrintOptions.bulkPrint())                     // only for bulk print
    .build();
```

A bundle starts with a title page and the contents page, which links to each document. Bookmarks
follow the sections. The watermark goes on documents' pages, not on the pages the toolkit
generates.

### Documents that cannot be included

By default, a document that cannot be fetched or converted is replaced by a page saying why, and
listed in the result's `failures()`. Each `DocFailure` has the document's title, a reason, and a
detail for logs:

| Reason | Placeholder page says | Retrying may help |
|---|---|---|
| `NOT_FOUND` | The document could not be found. It may have been deleted. | No |
| `ACCESS_DENIED` | The service was not given permission to open the document. | No |
| `UNAVAILABLE` | The document could not be retrieved because of a temporary technical problem. | Yes |
| `UNSUPPORTED_FORMAT` | This type of file cannot be included. | No |
| `CONVERSION_FAILED` | The document could not be converted to PDF. | No |
| `UNREADABLE` | The file could not be opened. It may be damaged or password protected. | No |
| `TOO_LARGE` | The document is too large to include. (The limit is 300 MB.) | No |

With `missingDocuments(MissingDocuments.FAIL)`, rendering throws instead (see [Errors](#errors)).

## Conversions

```java
// One document.
DocConversionRequest.of(DocSource.of(document));

// Several, joined in order, with nothing added between them.
DocConversionRequest.document(DocSource.of(order))
    .document(DocSource.of(schedule))
    .watermark(Watermark.image(draftPng))
    .build();
```

A conversion fails if any document cannot be converted: it never has placeholder pages.

## Bulk print

Add `print(...)` to either kind of request:

```java
// A letter: the coversheet as rendered, then each document print-ready.
DocConversionRequest.unchanged(DocSource.bytes(coversheet, "coversheet.pdf"))
    .document(DocSource.cdam(claimFormId))
    .document(DocSource.cdam(defenceFormId))
    .title("CPC-01 1234-5678-9012-3456")
    .print(PrintOptions.bulkPrint())
    .build();
```

`PrintOptions.bulkPrint()` is A4, PDF/X-1a:2001, CMYK, 300 dpi and a 20 mm margin, with landscape
pages turned onto the portrait page. It pads to an even page count, limits the output to 20 MiB,
and switches to greyscale above 80% of that limit. Change any of these with the builder:

```java
PrintOptions.bulkPrint().toBuilder()
    .resolutionDpi(150)               // 72–600; 150 dpi gives roughly half the file size
    .colour(PrintColour.GRAYSCALE)    // for mono print runs
    .marginMillimetres(20)
    .pageSize(PageSize.A4)
    .standard(PdfStandard.PDF_X_1A_2001)      // or NONE
    .outputCondition(OutputCondition.FOGRA39) // the printing condition in the output intent
    .rotateLandscapePages(true)
    .padToEvenPages(true)
    .maxOutputBytes(50L * 1024 * 1024)        // 0 for no limit
    .greyscaleAboveFraction(0.8)
    .build();
```

Printing uses the same conversion as everything else, then renders the PDF's pages for print:

- **A conversion** prints each document and joins them. With `padToEvenPages`, each document is
  padded to an even page count, as send-letter pads each document it merges, so every document
  starts on a new sheet. `unchanged(...)` documents keep their pages exactly as they are, so a
  coversheet's address stays where the envelope window expects it. Those pages are not checked,
  so a letter that has any is not identified as PDF/X-1a.
- **A bundle** is assembled first, then printed as a whole. Its links and bookmarks are lost.

Send-letter forwards a letter to Xerox unchanged only when it is one document with an even page
count. Otherwise it merges or pads it and saves the result as PDF 1.6, which is no longer
PDF/X-1a. So send the whole letter as one conversion request.

When the output is over `greyscaleAboveFraction` of `maxOutputBytes` in CMYK, it is printed
again in greyscale; Docmosis is not called again. Output still over `maxOutputBytes` fails with
`OUTPUT_TOO_LARGE`. Send-letter has no request size limit of its own, but its upload job runs out
of memory on large letters: in [FACT-2067](https://hmcts.atlassian.net/browse/FACT-2067) a 50 MB
letter uploaded and letters of around 60–70 MB failed. The 20 MiB default stays well clear of
that. Every page is rendered as an image, so several small documents can pass 20 MiB; Possessions
raises the limit to 50 MiB.

Every page is rendered to one opaque image, scaled to fit inside the margin and centred, so the
output meets these requirements whatever the source contained:

- **PDF/X-1a:2001**: PDF 1.3 with a classic cross-reference table, a file ID, the
  `GTS_PDFXVersion`/`GTS_PDFXConformance` keys, `/Trapped /False`, a `GTS_PDFX` output intent
  naming a registered printing condition (FOGRA39 by default, no profile embedded), a TrimBox on
  every page, DeviceCMYK or DeviceGray samples only, no transparency, and no fonts.
- **Image resolution**: no image prints denser than `resolutionDpi`.
- **Margin**: nothing is drawn within `marginMillimetres` of any edge.
- **Duplex**: the page count is even.

Costs of this approach:

- Text in the output is not selectable or searchable. That does not affect printing.
- Samples are stored lossless (Flate). A text page is about 100–200 KB at CMYK 300 dpi, and a
  page that is mostly photograph is about 1.3 MB. Greyscale or 150 dpi roughly halves this.
- CMYK separation uses a neutral formula, so black and grey print on the black plate only. It is
  not ICC-managed, so colour photographs print close to the original but not colour-exact.

## Errors

`render` and `renderAndUpload` throw `DocToolkitException`. Its `reason()` is one of:

| Reason | Meaning |
|---|---|
| `INVALID_REQUEST` | The request cannot be rendered as given, such as a bundle with nothing in it. |
| `DOCUMENTS_FAILED` | Documents could not be included: under `MissingDocuments.FAIL`, or in a conversion. `failures()` lists them. |
| `NOT_CONFIGURED` | A document needs Docmosis or CDAM, and the toolkit was not given it. |
| `OUTPUT_TOO_LARGE` | Print-ready output over `maxOutputBytes`, even in greyscale. |
| `ASSEMBLY_FAILED` | The documents could not be put together. |
| `UPLOAD_FAILED` | CDAM did not store or attach the result. A stored but unattached document is deleted. |
| `IO_FAILURE` | Local disk trouble. |

`isTransient()` says whether the same request may succeed later: a CDAM or Docmosis outage, an
upload failure, or disk trouble. A failed render leaves nothing behind in the temp directory,
even when it fails with an `Error` such as running out of memory.

The exception's message names each document and its reason, and never includes document content
or the Docmosis access key, so it can be logged and shown to a caseworker.

## Uploading

`renderAndUpload(request, caseId, eventId)` uploads the PDF to CDAM as the system user with
classification `PUBLIC`, then attaches it to the case. CDAM needs the case type and jurisdiction
to attach a document; the toolkit reads them from the decentralised runtime's `ccd.case_data`
table, so the case must already exist. The event id is added to the logging context while it
runs.

## Rendering in the background

Queue a request instead of rendering it in the callback:

```yaml
ccd:
  doc-toolkit:
    jobs:
      enabled: true
      worker:
        enabled: true     # false on instances that only queue jobs
```

```java
docJobs.submit(request, caseId, eventId);
```

The job, with the whole request, is written to the `bundling.bundle_job` table in the caller's
transaction, so it is queued only if the event commits. A worker renders it, stores the PDF in
CDAM and attaches it to the case, as `renderAndUpload` does, then hands the stored document to
the service's `DocJobHandler` bean to record:

```java
@Component
class BundleStored implements DocJobHandler {
  public void completed(DocJob job, DocRequest request, UploadedDoc uploaded) {
    bundles.save(job.caseId(), uploaded.document());   // the CCD Document CDAM returned
  }

  public void failed(DocJob job) {
    log.error("Bundle for {} failed: {}", job.caseId(), job.failure().orElseThrow().message());
  }
}
```

- **Retries**: a job is tried up to three times, 30 seconds and then a minute apart. A failure
  that may pass (CDAM, Docmosis or upload trouble) is retried. A document that is only
  temporarily unavailable fails the attempt while retries remain, and becomes a placeholder page
  on the last. Anything else fails the job at once.
- **The handler**: if `completed` throws, it is called again on the next attempt with the same
  document, without rendering or uploading again, so make it safe to repeat. After the last
  attempt the job fails with `HANDLER_FAILED`, and its message has the stored document's URL.
- **Crashes**: a worker holds each job under a five-minute lease. If it dies, another worker
  picks the job up when the lease runs out. A job whose every attempt crashed fails with
  `CRASHED`.
- **Workers** poll every second and render two jobs at a time each. Run them in a separate
  deployment from the instances handling CCD callbacks by setting `worker.enabled` to false on
  the callback instances; each worker instance needs the `DocJobHandler`.
- **What can be queued**: CDAM documents, bytes and HTML pages. A local file cannot be queued,
  because the instance running the job may not have it.
- `docJobs.find(id)` returns a job's state, attempts, result and failure.

The table and its migrations are document-bundling's, under the same Flyway location
(`bundling-db`), so a service that used document-bundling's outbox keeps its history. Jobs queued
in document-bundling's format are failed by the migration that reshapes the table.

## Local runs and tests

`ccd-sdk-test-support` has a Docmosis stub, `DocmosisStubController`. It answers `/rs/convert`
with a one-page PDF naming the file, and records what it converted. Turn it on in a local or
cftlib profile:

```yaml
ccd:
  doc-toolkit:
    docmosis-stub:
      enabled: true
    docmosis:
      convert-endpoint: http://localhost:${server.port}/stub/rs/convert
      access-key: stub
```

It is a controller in `uk.gov.hmcts.ccd.sdk.testing`, which the SDK's component scanning finds.
An application that does not scan `uk.gov.hmcts.ccd.sdk` adds it with
`@Import(DocmosisStubController.class)`.

The e2e test project's `CaseworkerCreateBundle` is a worked example. The cftlib suite renders it,
uploads it through the embedded CDAM, and checks the stitched PDF.

## The toolkit's own tests

The tests are behavioural, following [the testing strategy](../testing-strategy.md):

- **Requests end to end** (`BundleTest`, `BundleFailureTest`, `DocConversionTest`, `UploadTest`):
  CDAM is an in-memory implementation of `CaseDocumentClientApi`, Docmosis a stub.
- **The job outbox** (`DocJobsTest`) against a real Postgres migrated through the SDK's Flyway
  coordinator: Testcontainers, or the server in `DOC_TOOLKIT_TEST_POSTGRES` (a JDBC URL) on a
  machine without Docker. Each run creates a database there.
- **Print requirements as assertions** (`PrintRequirements`). Every print-ready output must pass a
  structural PDF/X-1a:2001 check (`PdfX1aChecker`), have no image above the resolution cap, and
  have nothing in the margin, checked from the placed geometry and by rendering each page.
- **Goldens** (`src/test/resources/golden/<scenario>/`) for print output, and characterisation
  goldens for bundle assembly ported from em-stitching. Regenerate print goldens with
  `./gradlew -p sdk :doc-toolkit:test -PupdateGoldens` and review the PNG diffs.
- **Send-letter passthrough**: `SendLetterReplay` copies send-letter-service's duplex and merge
  logic, and our output must come out of it byte for byte.
- **Ghostscript cross-check** (when `gs` is installed, and always on CI): an independent
  interpreter must render the output and agree that the margins are blank.
- **Real Docmosis** (opt-in): set `DOCMOSIS_CONVERT_ENDPOINT` and `DOCMOSIS_ACCESS_KEY` to convert
  the real office fixtures through a non-production Docmosis.

## Upgrading from document-bundling and document-conversion

The toolkit replaces both modules.

- **Dependency**: `com.github.hmcts:doc-toolkit` replaces `document-bundling` and
  `document-conversion`. Packages move from `uk.gov.hmcts.ccd.sdk.bundling.api` and
  `uk.gov.hmcts.ccd.sdk.conversion` to `uk.gov.hmcts.ccd.sdk.doctoolkit`.
- **Properties**: `ccd.bundling.*` and `ccd.conversion.*` are replaced by
  `ccd.doc-toolkit.docmosis.*` and `ccd.doc-toolkit.temp-directory`. Limits, concurrency and
  watermark images are no longer configured; set what you need on the request.
- **Entry point**: inject `DocToolkit` instead of `BundleRenderer` or `DocumentConverter`.
  `render(request, BundleExecutionContext)` becomes `render(request)` or
  `renderAndUpload(request, caseId, eventId)`.
- **Documents**: there are no `DocumentResolver`s or `DocumentReference`s. Pass the CCD
  `Document`, a CDAM id, or local content. Uploading and attaching moved into the toolkit, using
  your `DocToolkitSystemUser` bean (which replaces `BundleSystemUser`).
- **File types**: `PdfConverter` is gone. Use an `HtmlPage` for anything the toolkit cannot
  convert, including recordings, which `MediaPlaceholder` used to cover.
- **Cover pages** are any document (`coverPage(DocSource)`), not a Docmosis template.
- **Results** have the file, its page count and the documents that could not be included.
  Per-document placement, timings, warnings and metrics are gone; warnings are logged.
- **Always-up-to-date bundles are gone**: `CaseBundleDefinition`, `CaseBundles`,
  `ManagedCaseBundles`, `CaseBundleView`, coalesced submissions and the `bundling.bundle` table,
  which migration V0005 drops. Render or queue a bundle from an event instead.
- **The job outbox stays**, with a smaller API: `DocJobs.submit(request, caseId, eventId)` replaces
  `OutboxBundleJobService`, and a `DocJobHandler` receiving the stored CDAM document replaces
  `BundleJobCompletionHandler`. `ccd.doc-toolkit.jobs.enabled` and `jobs.worker.enabled` replace
  `ccd.bundling.job.*`; retry, lease and concurrency settings are fixed. Jobs still queued in the
  old format when V0005 runs are failed; submit them again.
- **Print**: `DocumentConverter.toPrintReadyLetter(title, parts, options)` becomes a
  `DocConversionRequest` with `unchanged(...)` for `PrintPart.asIs` and `document(...)` for
  `PrintPart.convert`, plus `print(options)`.
