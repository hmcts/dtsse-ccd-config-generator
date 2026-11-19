package uk.gov.hmcts.ccd.sdk.conversion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionException.Reason;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderException;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderService;

/**
 * The converters {@link DocumentConverter} registers before any of the caller's.
 */
final class BuiltInConverters {

  private BuiltInConverters() {
  }

  /**
   * A converter that renders print-ready output straight from its source, rather than from the
   * PDF {@link PdfConverter#convert} produces. Images keep their full resolution and EXIF
   * orientation this way, and a PDF is not copied first.
   */
  interface PrintsDirectly {
    int renderForPrint(ConversionSource source, PrintRenderer renderer, String title, Path work,
        Path output) throws ConversionException, IOException;
  }

  /**
   * PDFs are copied unchanged; {@link DocumentConverter} checks the copy like any output.
   */
  static final class Pdf implements PdfConverter, PrintsDirectly {
    @Override
    public Set<String> mediaTypes() {
      return Set.of(SupportedMediaTypes.PDF);
    }

    @Override
    public void convert(ConversionSource source, Path output, ConversionContext context)
        throws IOException {
      Files.copy(source.file(), output, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public int renderForPrint(ConversionSource source, PrintRenderer renderer, String title,
        Path work, Path output) throws ConversionException, IOException {
      try (PDDocument document = DocumentConverter.named(source,
          () -> PdfChecks.openUsablePdf(source.file(), work))) {
        return renderer.renderPdf(document, title, output);
      }
    }
  }

  /**
   * Images are drawn onto pages; see {@link ImageToPdf} and {@link PrintRenderer}.
   */
  static final class Image implements PdfConverter, PrintsDirectly {
    @Override
    public Set<String> mediaTypes() {
      return SupportedMediaTypes.IMAGES;
    }

    @Override
    public void convert(ConversionSource source, Path output, ConversionContext context)
        throws ConversionException {
      DocumentConverter.named(source, () -> ImageToPdf.write(source.file(), output));
    }

    @Override
    public int renderForPrint(ConversionSource source, PrintRenderer renderer, String title,
        Path work, Path output) throws ConversionException {
      try {
        return renderer.renderImage(source.file(), title, output);
      } catch (IOException e) {
        throw new ConversionException(Reason.INVALID_CONTENT,
            source.fileName() + ": the image could not be decoded", e);
      }
    }
  }

  /** Office formats are converted by Docmosis. */
  static final class DocmosisOffice implements PdfConverter {
    private final DocmosisRenderService docmosis;

    DocmosisOffice(DocmosisRenderService docmosis) {
      this.docmosis = docmosis;
    }

    @Override
    public Set<String> mediaTypes() {
      return SupportedMediaTypes.OFFICE;
    }

    @Override
    public void convert(ConversionSource source, Path output, ConversionContext context)
        throws ConversionException, IOException {
      Path converted;
      try {
        converted = docmosis.convertToPdf(source.file(), source.fileName(), source.mediaType());
      } catch (DocmosisRenderException e) {
        throw new ConversionException(Reason.CONVERTER_FAILED,
            "Docmosis could not convert " + source.fileName() + ": " + e.getMessage(),
            e.isTransientFailure(), e);
      }
      // Docmosis writes to its own directory; move the result so the conversion owns it.
      try {
        Files.move(converted, output, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        Files.deleteIfExists(converted);
        throw e;
      }
    }
  }
}
