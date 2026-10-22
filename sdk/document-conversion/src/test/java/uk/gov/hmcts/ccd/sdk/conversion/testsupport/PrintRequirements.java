package uk.gov.hmcts.ccd.sdk.conversion.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import uk.gov.hmcts.ccd.sdk.conversion.PdfStandard;
import uk.gov.hmcts.ccd.sdk.conversion.PrintColour;
import uk.gov.hmcts.ccd.sdk.conversion.PrintOptions;

/**
 * The bulk print requirements, as assertions any print-ready output must pass, whatever the
 * source was.
 *
 * <ol>
 *   <li>PDF/X-1a compliant, when that standard is requested ({@link PdfX1aChecker});</li>
 *   <li>no image denser than the configured resolution;</li>
 *   <li>nothing within the margin of any edge: checked both from the placed geometry and by
 *       rendering every page and requiring the margin band to be paper white;</li>
 * </ol>
 *
 * <p>plus the layout the options ask for: the paper size, the colour space, and an even page
 * count when padding is on. A page with no image must be blank: it is duplex padding.
 */
public final class PrintRequirements {
  private static final float RENDER_DPI = 50;
  private static final double TOLERANCE_MM = 0.05;
  private static final int PAPER_WHITE = 250;

  private PrintRequirements() {
  }

  /** Asserts every requirement. */
  public static void assertPrintReady(Path pdf, PrintOptions options) throws IOException {
    assertPrintReady(pdf, options, 1);
  }

  /**
   * Asserts every requirement from page {@code firstPage} on; earlier pages were added as they
   * are (a coversheet), so only they may break PDF/X-1a.
   */
  public static void assertPrintReady(Path pdf, PrintOptions options, int firstPage)
      throws IOException {
    if (options.standard() == PdfStandard.PDF_X_1A_2001) {
      assertThat(PdfX1aChecker.violations(pdf)).as("PDF/X-1a:2001 violations")
          .allSatisfy(violation -> assertThat(firstPage > 1
              && violation.matches("page ([0-9]+): .*")
              && Integer.parseInt(violation.replaceAll("page ([0-9]+): .*", "$1")) < firstPage)
              .as(violation).isTrue());
    }
    double margin = options.marginMillimetres();
    String colourSpace = options.colour() == PrintColour.CMYK ? "DeviceCMYK" : "DeviceGray";
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      List<PrintFacts.Page> pages = PrintFacts.pages(document);
      if (options.padToEvenPages()) {
        assertThat(pages.size() % 2).as("page count %d is even for duplex", pages.size())
            .isZero();
      }
      for (int i = firstPage - 1; i < pages.size(); i++) {
        PrintFacts.Page page = pages.get(i);
        String where = "page " + (i + 1);
        assertThat(page.widthMm()).as(where + " width")
            .isCloseTo(options.pageSize().widthMillimetres(), org.assertj.core.data.Offset
                .offset(0.1));
        assertThat(page.heightMm()).as(where + " height")
            .isCloseTo(options.pageSize().heightMillimetres(), org.assertj.core.data.Offset
                .offset(0.1));
        assertThat(page.hasText()).as(where + " has no live text to reflow").isFalse();
        for (PrintFacts.PlacedImage image : page.images()) {
          assertThat(image.dpiX()).as(where + " horizontal dpi")
              .isLessThanOrEqualTo(options.resolutionDpi() + 0.05);
          assertThat(image.dpiY()).as(where + " vertical dpi")
              .isLessThanOrEqualTo(options.resolutionDpi() + 0.05);
          assertThat(List.of(image.leftMm(), image.bottomMm(), image.rightMm(), image.topMm()))
              .as(where + " image distance from each edge (L, B, R, T)")
              .allSatisfy(edge -> assertThat(edge).isGreaterThanOrEqualTo(margin - TOLERANCE_MM));
          assertThat(image.colourSpace()).as(where + " colour space").isEqualTo(colourSpace);
          assertThat(image.softMask()).as(where + " soft mask").isFalse();
        }
      }
    }
    List<BufferedImage> renders = PrintFacts.render(pdf, RENDER_DPI);
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      List<PrintFacts.Page> pages = PrintFacts.pages(document);
      for (int i = firstPage - 1; i < renders.size(); i++) {
        boolean padding = pages.get(i).images().isEmpty();
        // A padding page must be blank all over: a band as wide as the page.
        assertMarginBlank(renders.get(i), padding ? 300 : margin, "page " + (i + 1));
      }
    }
  }

  /** Requires every pixel within the margin band (less one pixel) to be paper white. */
  public static void assertMarginBlank(BufferedImage page, double marginMm, String where) {
    int band = (int) Math.floor(marginMm / 25.4 * RENDER_DPI) - 1;
    for (int y = 0; y < page.getHeight(); y++) {
      for (int x = 0; x < page.getWidth(); x++) {
        boolean inBand = x < band || y < band || x >= page.getWidth() - band
            || y >= page.getHeight() - band;
        if (inBand) {
          int grey = page.getRGB(x, y) & 0xFF;
          if (grey < PAPER_WHITE) {
            throw new AssertionError(where + ": ink at (" + x + ", " + y + ") px, within "
                + marginMm + "mm of the edge (grey level " + grey + ")");
          }
        }
      }
    }
  }
}
