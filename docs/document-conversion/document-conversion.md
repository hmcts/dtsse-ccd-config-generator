# Document conversion

`sdk/document-conversion` (`com.github.hmcts:document-conversion`) converts documents to PDF
inside your service. It can also lay the PDF out for print, which covers the bulk print (Xerox)
requirements: PDF/X-1a, a resolution cap on images, and a blank margin. Document bundling uses the
same conversions, so a document converts the same way whether it goes into a bundle or into a
letter.

## Getting started

```groovy
implementation 'com.github.hmcts:document-conversion:<version>'
```

```yaml
ccd:
  conversion:
    docmosis:                                  # leave out if you only convert PDFs and images
      convert-endpoint: ${DOCMOSIS_ENDPOINT}   # /rs/convert
      access-key: ${DOCMOSIS_ACCESS_KEY}
```

The auto-configuration provides a `DocumentConverter` bean. If the application already has a
`DocmosisRenderService` bean, for example the one document bundling creates from
`ccd.bundling.docmosis.*`, the converter uses that bean and ignores `ccd.conversion.docmosis.*`.
Outside Spring, build the converter yourself:

```java
DocumentConverter converter = DocumentConverter.builder()
    .docmosis(new HttpDocmosisRenderService(
        DocmosisConnection.withDefaults(convertEndpoint, accessKey), tempDir))
    .build();
```

## Converting for bulk print

```java
try (ConvertedPdf pdf = converter.toPrintReadyPdf(
    ConversionSource.of(file, "tenancy-agreement.docx", declaredMediaType),
    PrintOptions.bulkPrint())) {
  sendLetter(pdf.open());
}
```

`PrintOptions.bulkPrint()` is A4, PDF/X-1a:2001, CMYK, 300 dpi and a 20 mm margin, with landscape
pages turned onto the portrait page. It pads the output to an even page count, limits it to
20 MiB, and switches to greyscale above 80% of that limit. You can change any of these with the
builder:

```java
PrintOptions.builder()
    .resolutionDpi(150)               // 72–600; 150 dpi gives roughly half the file size
    .colour(PrintColour.GRAYSCALE)    // for mono print runs
    .marginMillimetres(20)
    .pageSize(PageSize.A4)
    .standard(PdfStandard.PDF_X_1A_2001)      // or NONE
    .outputCondition(OutputCondition.FOGRA39) // the printing condition in the output intent
    .rotateLandscapePages(true)
    .padToEvenPages(true)
    .maxOutputBytes(20L * 1024 * 1024)        // 0 for no limit
    .greyscaleAboveFraction(0.8)
    .build();
```

### A letter with a coversheet

Send-letter forwards a letter to Xerox unchanged only when the letter is one document with an
even page count. Otherwise it adds a blank page or merges the documents, and saves the result as
PDF 1.6, which is no longer PDF/X-1a. So build the whole letter as one document:

```java
try (ConvertedPdf letter = converter.toPrintReadyLetter("Claim pack", List.of(
    PrintPart.asIs(coversheet, "coversheet"),        // pages kept exactly as they are
    PrintPart.convert(ConversionSource.of(evidence, "photo.jpg", "image/jpeg")),
    PrintPart.convert(ConversionSource.of(statement, "statement.docx", docxType))),
    PrintOptions.bulkPrint())) {
  // send letter.file() to send-letter as a single document
}
```

`asIs` parts keep their pages exactly as they are. Only a missing TrimBox is added. Their content
is not re-rendered, so a coversheet's address stays where the envelope window expects it. Those
pages keep their own colour and fonts, so they are the only pages that can fall outside
PDF/X-1a. Every `convert` page meets the requirements below.

Because the converter does not check `asIs` pages, a letter that has any is not identified as
PDF/X-1a. It has no `GTS_PDFX` keys or output intent, and its header is whatever version its
parts need. It keeps the rest of the file structure, so the converted pages reach the printer
as they were rendered. The result carries a `STANDARD_NOT_CLAIMED` warning. A letter made only
of `convert` parts is identified as PDF/X-1a.

### The size limit

When the letter is over `greyscaleAboveFraction` of `maxOutputBytes` in CMYK, every converted
part is converted again in greyscale, and the result carries a `COLOUR_REDUCED_FOR_SIZE` warning.
Office parts are not sent to Docmosis again: the greyscale pass reuses the PDFs Docmosis returned
the first time.
A letter that is still over `maxOutputBytes` in greyscale fails with `OUTPUT_TOO_LARGE`.
send-letter has no request size limit of its own. The 20 MiB default keeps a letter well clear of
the out-of-memory errors its upload job has hit on batches of about 95 MB zipped.

Every source page is rendered to one opaque image, scaled to fit inside the margin and centred.
Each output page holds only that image, so the output meets the requirements whatever the source
contained:

- **PDF/X-1a:2001**: PDF 1.3 with a classic cross-reference table, a file ID, the
  `GTS_PDFXVersion`/`GTS_PDFXConformance` keys, `/Trapped /False`, a `GTS_PDFX` output intent
  that names a registered printing condition (FOGRA39 by default, with no profile embedded), a
  TrimBox on every page, DeviceCMYK or DeviceGray samples only, no transparency, and no fonts.
- **Image resolution**: no image prints denser than `resolutionDpi`. Document pages are rendered
  at exactly that resolution. Larger images are downsampled to it, and images are never enlarged
  past one pixel per point.
- **Margin**: nothing is drawn within `marginMillimetres` of any edge.
- **Duplex**: the page count is even, so send-letter forwards the file unchanged.

Costs of this approach:

- Text in the output is not selectable or searchable. That does not affect printing.
- Samples are stored lossless (Flate). A text page is about 100–200 KB at CMYK 300 dpi, and a
  page that is mostly photograph is about 1.3 MB. Greyscale or 150 dpi roughly halves this.
- CMYK separation uses a neutral formula, so black and grey print on the black plate only. It is
  not ICC-managed, so colour photographs print close to the original but not colour-exact.

## Plain conversion

`converter.toPdf(source)` converts without changing the layout. A PDF is validated and copied. An
image is centred on a Letter page at its natural size. An office document is returned as Docmosis
produced it. This is the conversion document bundling uses.

## Supported types

| Upload | Media type | Converted by |
|---|---|---|
| PDF | `application/pdf` | validated (unencrypted, parseable, at least one page) |
| JPG/JPEG, PNG, BMP, GIF | `image/*` | decoded in-process; PNG transparency prints white; for print, a JPEG's EXIF orientation is applied, so phone photos print upright |
| TIF/TIFF | `image/tiff` | decoded in-process, one page per frame |
| DOC/DOT, DOCX, DOTX | Word types | Docmosis |
| XLS/XLT/XLA, XLSX, XLTX, XLSB | Excel types | Docmosis |
| PPT/POT/PPS/PPA, PPTX, POTX, PPSX | PowerPoint types | Docmosis |
| TXT, RTF, CSV | `text/plain`, `application/rtf`, `text/csv` | Docmosis |

The converter checks the declared media type against the content first, using the same detection
as document bundling. Content that is clearly another type is converted as that type, with a
`MEDIA_TYPE_MISMATCH` warning. A missing or `application/octet-stream` type is inferred from the
file extension, with a `MEDIA_TYPE_INFERRED` warning. Common store aliases, such as `text/rtf`
and `image/x-ms-bmp`, are accepted as they are. A failure throws `ConversionException` with a
`reason()`:

- `UNSUPPORTED_MEDIA_TYPE`
- `MEDIA_TYPE_CONFLICT`
- `INVALID_CONTENT`
- `ENCRYPTED`
- `CONVERTER_NOT_CONFIGURED`
- `CONVERTER_FAILED`
- `OUTPUT_TOO_LARGE`
- `IO_FAILURE`

`OUTPUT_TOO_LARGE` comes from print conversion. `isTransientFailure()` is true only when Docmosis was unavailable, which is the case worth
retrying.

The result is written to a private directory under `ccd.conversion.temp-directory` (default
`java.io.tmpdir`). Closing the `ConvertedPdf` deletes it. A failed conversion leaves nothing
behind.

## Testing

The module's tests are behavioural, following [the testing strategy](../testing-strategy.md):

- **Requirements as assertions** (`PrintRequirements`). Every print-ready output must pass a
  structural PDF/X-1a:2001 check (`PdfX1aChecker`). No open-source PDF/X validator exists, so
  this checker encodes the standard's machine-checkable rules. Every output must also have no
  image above the resolution cap, and nothing in the margin. The margin is checked twice: from
  the placed geometry, and by rendering each page and requiring the margin band to be white. A
  sanity test confirms the checker reports the violations in non-conformant input.
- **Goldens** (`src/test/resources/golden/<scenario>/`). `facts.json` records the page and trim
  sizes, and each image's pixel size, colour space, encoding, distance from each edge and
  effective resolution. It is compared exactly. `page-N.png` is a 30 dpi preview, compared with a
  tolerance that absorbs anti-aliasing but catches a moved margin or a turned page. There are 14
  scenarios, covering the real em-stitching fixtures and generated edge cases: multi-frame TIFF,
  alpha PNG, transparency, full-bleed, landscape and `/Rotate` pages, and a 24 MP photo. To
  regenerate them, run `./gradlew -p sdk :document-conversion:test -PupdateGoldens` and review the
  PNG diffs.
- **Send-letter passthrough**: `SendLetterReplay` copies send-letter-service's own duplex and
  merge logic. A test requires our output to come out of it byte for byte, so PDF/X-1a reaches
  Xerox. Another test builds a letter behind a coversheet and checks that the coversheet renders
  exactly as it did before.
- **Size selector**: the same photo is kept in colour at 50% of the limit, switched to greyscale
  at 100%, and refused when the limit is below its greyscale size.
- **Every Possessions upload extension** converts for print, and office formats reach Docmosis
  with the right media type.
- **Ghostscript cross-check** (runs when `gs` is installed). An independent interpreter must
  render the output without complaint and agree that the margins are blank.
- **Real Docmosis** (opt-in). Set `DOCMOSIS_CONVERT_ENDPOINT` and `DOCMOSIS_ACCESS_KEY` to
  convert the real office fixtures through a non-production Docmosis and check the output against
  the same requirements.
