package uk.gov.hmcts.ccd.sdk.bundling.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adversarial probes against {@link PdfSemantics}, produced by the characterisation-baseline
 * review. Originally these documented extractor weaknesses; the extractor has since been fixed
 * (bounded, region-restricted page-number-stamp heuristic; position-sorted whitespace-normalised
 * text), so each probe now asserts the corrected behaviour.
 */
class AdversarialReviewTest {

  @TempDir
  Path tempDir;

  /**
   * FINDING (extractor crash, FIXED): {@code PdfSemantics} used to feed any digits-only line to
   * {@code Integer.parseInt}, so a 16-digit CCD case reference — near-certain content in HMCTS
   * bundle documents — threw {@link NumberFormatException} and aborted the whole extraction.
   * The stamp heuristic is now bounded to 1–4 digits, so long numeric content is just text.
   */
  @Test
  void extractionSurvivesLongNumericLines() throws Exception {
    Path pdf = pdfWithLines(tempDir.resolve("caseref.pdf"),
        "Case reference:", "1234567890123456");

    assertThatCode(() -> PdfSemantics.extract(pdf)).doesNotThrowAnyException();

    ObjectNode facts = PdfSemantics.extract(pdf);
    assertThat(facts.get("pages").get(0).get("text").asText())
        .contains("1234567890123456");
    assertThat(facts.get("pages").get(0).get("pageNumberStamps")).isEmpty();
  }

  /**
   * FINDING (draw-order sensitivity, FIXED): the per-page text fact used to depend on
   * content-stream draw order, contradicting the policy's claim of positional tolerance.
   * Extraction is now position-sorted, so two visually identical pages whose producers emit
   * text operators in a different order extract to identical text.
   */
  @Test
  void perPageTextIsIndependentOfContentStreamDrawOrder() throws Exception {
    Path stampLast = pdfWithRuns(tempDir.resolve("stamp-last.pdf"), true);
    Path stampFirst = pdfWithRuns(tempDir.resolve("stamp-first.pdf"), false);

    String textA = PdfSemantics.extract(stampLast).get("pages").get(0).get("text").asText();
    String textB = PdfSemantics.extract(stampFirst).get("pages").get(0).get("text").asText();

    // Same glyphs at the same coordinates; only the operator order differs.
    assertThat(textA).isEqualTo(textB).isEqualTo("7\nBody text");
  }

  /**
   * FINDING (stamp misclassification, FIXED): {@code printedPageNumbers} used to be a purely
   * lexical heuristic that reported any digits-only content line (e.g. chart axis labels
   * 100/200/300) as a page-number stamp. The replacement {@code pageNumberStamps} fact is
   * region-restricted — only digit lines within 60pt of the top or bottom page edge (where all
   * six em-stitching PaginationStyle positions print) qualify — and carries coordinates.
   */
  @Test
  void contentDigitsOutsideTheStampBandAreNotStamps() throws Exception {
    // 100/700 in text space: mid-page vertically — content, not a stamp.
    Path pdf = pdfWithLines(tempDir.resolve("content-digits.pdf"),
        "Amount owed:", "300");

    ObjectNode facts = PdfSemantics.extract(pdf);

    assertThat(facts.get("pages").get(0).get("pageNumberStamps")).isEmpty();
    assertThat(facts.get("pages").get(0).get("text").asText()).contains("300");
  }

  private static Path pdfWithLines(Path pdf, String... lines) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        cs.newLineAtOffset(100, 700);
        for (String line : lines) {
          cs.showText(line);
          cs.newLineAtOffset(0, -20);
        }
        cs.endText();
      }
      doc.save(pdf.toFile());
    }
    return pdf;
  }

  private static Path pdfWithRuns(Path pdf, boolean bodyFirst) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        if (bodyFirst) {
          run(cs, font, 100, 700, "Body text");
          run(cs, font, 45, 780, "7");
        } else {
          run(cs, font, 45, 780, "7");
          run(cs, font, 100, 700, "Body text");
        }
      }
      doc.save(pdf.toFile());
    }
    return pdf;
  }

  private static void run(PDPageContentStream cs, PDType1Font font, float x, float y, String s)
      throws Exception {
    cs.beginText();
    cs.setFont(font, 12);
    cs.newLineAtOffset(x, y);
    cs.showText(s);
    cs.endText();
  }
}
