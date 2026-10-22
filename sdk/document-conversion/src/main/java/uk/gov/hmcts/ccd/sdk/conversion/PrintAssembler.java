package uk.gov.hmcts.ccd.sdk.conversion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Joins print-ready parts into one letter and pads it to an even page count, keeping the
 * PDF/X-1a file structure: send-letter passes a single even-page document to the printer
 * byte for byte, but re-saves anything else as PDF 1.6.
 */
final class PrintAssembler {

  private PrintAssembler() {
  }

  /**
   * Writes the joined letter to {@code output}; returns its page count.
   */
  static int join(List<Path> parts, String title, PrintOptions options, Path scratchDirectory,
      Path output) throws IOException {
    List<PDDocument> sources = new ArrayList<>();
    try (PDDocument letter = new PDDocument(
        MemoryUsageSetting.setupMixed(PdfChecks.MAX_CACHE_HEAP_BYTES)
            .setTempDir(scratchDirectory.toFile()).streamCache)) {
      PDFMergerUtility merger = new PDFMergerUtility();
      // Sources stay open until the letter is saved: their streams are read during the save.
      for (Path part : parts) {
        PDDocument source = PdfChecks.load(part, scratchDirectory);
        sources.add(source);
        merger.appendDocument(letter, source);
      }
      for (PDPage page : letter.getPages()) {
        if (page.getCOSObject().getDictionaryObject(COSName.TRIM_BOX) == null
            && page.getCOSObject().getDictionaryObject(COSName.ART_BOX) == null) {
          page.setTrimBox(copy(page.getMediaBox()));
        }
      }
      if (options.padToEvenPages() && letter.getNumberOfPages() % 2 == 1) {
        PDRectangle last = letter.getPage(letter.getNumberOfPages() - 1).getMediaBox();
        PDPage blank = new PDPage(copy(last));
        blank.setTrimBox(copy(last));
        letter.addPage(blank);
      }
      if (options.standard() == PdfStandard.PDF_X_1A_2001) {
        PdfX1a.identify(letter, title, options.outputCondition());
        PdfX1a.save(letter, output);
      } else {
        letter.getDocumentInformation().setTitle(title);
        letter.save(output.toFile());
      }
      return letter.getNumberOfPages();
    } finally {
      for (PDDocument source : sources) {
        source.close();
      }
    }
  }

  private static PDRectangle copy(PDRectangle box) {
    return new PDRectangle(box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(),
        box.getHeight());
  }
}
