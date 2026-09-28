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
    docmosis:                         # leave out if you have no Word/Excel/PowerPoint documents
      convert-endpoint: ${DOCMOSIS_ENDPOINT}
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
Nothing is left on disk. A later change will add an opt-in policy that renders a placeholder page
for a document that could not be fetched and lists it on the result instead of failing, so a
caseworker can still get a bundle and see what is missing (see the scope document).

## What you get by default

| Media type | Handling |
|---|---|
| `application/pdf` | Checked and passed through |
| PNG, JPEG, TIFF, BMP, GIF | Placed on a page of the right size |
| Word, Excel, PowerPoint, RTF, plain text | Converted through the shared Docmosis service |
| anything else | The bundle fails, naming the type and the types that are supported |

Presentation is chosen from presets: `BundlePresentation.courtDefault()` plus `withTableOfContents`,
`withSectionCoverSheets`, `withDocumentCoverSheets`, `withPageNumbers` and
`withConfidentialMarking`. Bookmarks follow the section tree. There is deliberately no way to put
free-form text or graphics over evidence pages.

Limits default to 100 documents, 300 MB per source, 1 GB output and 1,000 pages; change them per
field with `ccd.bundling.limits.*` or `.limits(...)`. `max-concurrent-renders` (default 2) caps how
many renders hold a PDFBox scratch buffer at once; extra renders wait their turn.

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

### Custom index and cover pages

The contents page, section and document cover sheets and the title page are generated by the
module from your titles and dates. Templated cover pages and watermarks arrive in a later change
(see the scope document); until then the presets above are the only presentation knobs.

### Storing the result

The module hands back a file; storing it is a port in the loosest sense, you just call your own
code. The e2e `BundlePublisher` uploads to CDAM, attaches the document to the case and builds the
service's `CaseBundle` from `result.documents()`.

## Testing

The module's tests are behavioural: a real PDF layer, an in-memory resolver, a stub Docmosis on a
local HTTP server, and a characterisation suite that renders 12 scenarios and compares them field
by field with goldens generated from `em-stitching-api` (`src/test/resources/characterisation`; the
few deliberate differences are listed on `CharacterisationRegressionTest`). The e2e project proves
the full stack: a decentralised event rendering through the embedded CCD and CDAM, and a failing
bundle surfacing its error with nothing uploaded. `BundleRenderer` is an interface, so unit tests of
your event handlers can fake it.

## What comes next

This is the first of a stack of changes. [document-bundling-scope.md](document-bundling-scope.md)
has the design goals, the delivery plan, the feature matrix and the one-click bundle requirements
coverage. In short: logging and metrics follow in PR2, the job outbox for asynchronous bundling in
PR3, and the rest of the microservice's rendering features (Docmosis cover pages, watermarks,
source bookmarks, media-type detection, readability checks) plus audio and video link pages in PR4.
