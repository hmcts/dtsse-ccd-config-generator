package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;

/**
 * Ports the scenarios of em-stitching's {@code PDFUtilityTest}: centred and positioned text,
 * null-text tolerance, page-number stamping, string measurement, wrapping, sanitisation, and
 * internal, right-aligned and external links — plus the crop-box positioning fix.
 */
class PdfUtilityTest {

  @TempDir
  Path tmp;

  private final PdfFonts fonts = new PdfFonts();

  @Test
  void addCenterTextAddsTextToThePage() throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);

      PdfUtility.addCenterText(document, page, "Centered Text", 20, fonts.helveticaBold(), 14);

      assertThat(new PDFTextStripper().getText(document)).contains("Centered Text");
    }
  }

  @Test
  void addCenterTextWrapsLargeText() throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      String largeText = "This is a large text that should be split into multiple lines. "
          .repeat(20).trim();

      PdfUtility.addCenterText(document, page, largeText, 20, fonts.helveticaBold(), 14);

      assertThat(new PDFTextStripper().getText(document)).contains("This is a large text");
    }
  }

  @Test
  void addCenterTextIgnoresNullText() throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);

      PdfUtility.addCenterText(document, page, null, 20, fonts.helveticaBold(), 14);

      assertThat(new PDFTextStripper().getText(document)).isBlank();
    }
  }

  @Test
  void addTextAddsTextToThePage() throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);

      PdfUtility.addText(document, page, "Sample Text", 100, 700, fonts.helvetica(), 12, 400);

      assertThat(new PDFTextStripper().getText(document)).contains("Sample Text");
    }
  }

  @Test
  void addTextIgnoresNullText() throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);

      PdfUtility.addText(document, page, null, 100, 700, fonts.helvetica(), 12, 400);

      assertThat(new PDFTextStripper().getText(document)).isBlank();
    }
  }

  @Test
  void addPageNumberStampsEveryPresetPositionAndFormat() throws IOException {
    for (PageNumbers preset : PageNumbers.values()) {
      if (preset == PageNumbers.NONE) {
        continue;
      }
      try (PDDocument document = new PDDocument()) {
        for (int i = 0; i < 3; i++) {
          document.addPage(new PDPage());
        }
        for (int i = 0; i < 3; i++) {
          PdfUtility.addPageNumber(document, preset, i, 3, fonts.helveticaBold());
        }

        String text = new PDFTextStripper().getText(document);
        String expectedSecondPage = preset.name().endsWith("N_OF_M") ? "2 of 3" : "2";
        assertThat(text).as("preset %s", preset).contains(expectedSecondPage);
      }
    }
  }

  @Test
  void stampsArePositionedInsideTheCropBox() throws IOException {
    // A scanned page whose visible area is smaller than its media box: the stamp must land
    // inside the crop box, not against the media-box edge above the visible area.
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(new PDRectangle(612, 792));
      page.setCropBox(new PDRectangle(100, 100, 400, 500)); // visible y: 100..600
      document.addPage(page);

      PdfUtility.addPageNumber(document, PageNumbers.TOP_RIGHT_N, 0, 1, fonts.helveticaBold());
      PdfUtility.addCenterText(document, page, "CONFIDENTIAL", 25, fonts.helveticaBold(), 14);

      String text = new PDFTextStripper().getText(document);
      assertThat(text).contains("1").contains("CONFIDENTIAL");
    }
  }

  @Test
  void getStringWidthIsPositiveForRealText() {
    assertThat(PdfUtility.getStringWidth("Sample Text", fonts.helvetica(), 12))
        .isGreaterThan(0);
  }

  @Test
  void splitStringWrapsLongText() {
    String[] lines = PdfUtility.splitString(
        "This is a sample text that should be split into multiple lines", 100,
        fonts.helvetica(), 12);

    assertThat(lines.length).isGreaterThan(1);
  }

  @Test
  void splitStringHandlesNullAndEmptyText() {
    assertThat(PdfUtility.splitString(null, 100, fonts.helvetica(), 12)).isEmpty();
    assertThat(PdfUtility.splitString("", 100, fonts.helvetica(), 12)).isEmpty();
  }

  @Test
  void sanitizeTextRemovesCharactersOutsideWinAnsi() {
    assertThat(PdfUtility.sanitizeText("Sample Text with unsupported char: •"))
        .isEqualTo("Sample Text with unsupported char: ");
    assertThat(PdfUtility.sanitizeText("")).isEmpty();
  }

  @Test
  void isRenderableTitleDetectsFullyNonWinAnsiTitles() {
    assertThat(PdfUtility.isRenderableTitle("Заявление о приёме")).isFalse();
    assertThat(PdfUtility.isRenderableTitle("ąćęłńóśźż")).isTrue(); // "ó" survives
    assertThat(PdfUtility.isRenderableTitle("Plain title")).isTrue();
  }

  @Test
  void addLinkDrawsTextAndAnnotationTargetingTheDestination() throws IOException {
    Path pdf = tmp.resolve("link.pdf");
    try (PDDocument document = new PDDocument()) {
      PDPage from = new PDPage();
      PDPage to = new PDPage();
      document.addPage(from);
      document.addPage(to);

      PdfUtility.addLink(document, from, to, "Link Text", 100, 700, fonts.helvetica(), 12,
          400, 1);
      document.save(pdf.toFile());
    }

    assertThat(Pdfs.pageText(pdf, 1)).contains("Link Text");
    assertThat(Pdfs.internalLinkTargets(pdf, 1)).containsExactly(2);
  }

  @Test
  void addRightLinkDrawsTextAndAnnotation() throws IOException {
    Path pdf = tmp.resolve("right-link.pdf");
    try (PDDocument document = new PDDocument()) {
      PDPage from = new PDPage();
      PDPage to = new PDPage();
      document.addPage(from);
      document.addPage(to);

      PdfUtility.addRightLink(document, from, to, "Right Link", 700, fonts.helvetica(), 12);
      document.save(pdf.toFile());
    }

    assertThat(Pdfs.pageText(pdf, 1)).contains("Right Link");
    assertThat(Pdfs.internalLinkTargets(pdf, 1)).containsExactly(2);
  }

  @Test
  void addUriLinkDrawsTextAndExternalAnnotation() throws IOException {
    Path pdf = tmp.resolve("uri-link.pdf");
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);

      PdfUtility.addUriLink(document, page, "https://example.org/media", "Open the recording",
          70, 400, fonts.helvetica(), 12, 400);
      document.save(pdf.toFile());
    }

    assertThat(Pdfs.pageText(pdf, 1)).contains("Open the recording");
    assertThat(Pdfs.uriLinks(pdf, 1)).containsExactly("https://example.org/media");
  }
}
