package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Step 6 of the pipeline: evidence-readability inspection of each converted PDF before assembly.
 * Loadable, unencrypted, at least one page, sane page dimensions; extractable-text presence is
 * recorded as a report fact (a warning), never a failure — merging cannot repair an inaccessible
 * source, and the module does not pretend otherwise.
 */
final class PdfInspection {

  /** PDF user-space page dimensions accepted as sane: positive up to the format maximum. */
  private static final float MAX_PAGE_DIMENSION_POINTS = 14_400f;
  private static final long MAX_CACHE_HEAP_BYTES = 16L * 1024 * 1024;
  private static final int TEXT_SAMPLE_PAGES = 20;

  /**
   * The inspection facts for one converted PDF.
   *
   * @param pageCount the document's page count
   * @param hasExtractableText whether any of the sampled pages yielded extractable text
   */
  record Facts(int pageCount, boolean hasExtractableText) {
  }

  /** A typed inspection failure with a log-safe detail. */
  static final class InspectionException extends Exception {

    InspectionException(String message) {
      super(message);
    }

    InspectionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private PdfInspection() {
  }

  /**
   * Inspects one converted PDF.
   *
   * @param pdf the converted PDF file
   * @param scratchDirectory where the bounded PDFBox cache may spill
   * @return the inspection facts
   * @throws InspectionException if the PDF is encrypted, empty, malformed, or has insane page
   *     dimensions
   */
  static Facts inspect(Path pdf, Path scratchDirectory) throws InspectionException {
    MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(MAX_CACHE_HEAP_BYTES)
        .setTempDir(scratchDirectory.toFile());
    try (PDDocument document = Loader.loadPDF(pdf.toFile(), memory.streamCache)) {
      if (document.isEncrypted()) {
        throw new InspectionException("The converted PDF is encrypted");
      }
      int pages = document.getNumberOfPages();
      if (pages < 1) {
        throw new InspectionException("The converted PDF has no pages");
      }
      for (int i = 0; i < pages; i++) {
        PDPage page = document.getPage(i);
        PDRectangle box = page.getMediaBox();
        if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0
            || box.getWidth() > MAX_PAGE_DIMENSION_POINTS
            || box.getHeight() > MAX_PAGE_DIMENSION_POINTS) {
          throw new InspectionException(
              "Page " + (i + 1) + " has insane dimensions: "
                  + (box == null ? "no media box" : box.getWidth() + "x" + box.getHeight()));
        }
      }
      return new Facts(pages, hasExtractableText(document, pages));
    } catch (InvalidPasswordException e) {
      throw new InspectionException("The converted PDF is password protected", e);
    } catch (IOException e) {
      throw new InspectionException("The converted PDF is corrupt or unreadable", e);
    }
  }

  private static boolean hasExtractableText(PDDocument document, int pages) {
    try {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setStartPage(1);
      stripper.setEndPage(Math.min(pages, TEXT_SAMPLE_PAGES));
      return !stripper.getText(document).isBlank();
    } catch (IOException | RuntimeException e) {
      // Text extraction is a report fact, not a gate; failure to extract means "no text found".
      return false;
    }
  }
}
