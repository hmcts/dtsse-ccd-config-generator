package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

final class GeneratedPages {
  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);
  private static final int BODY_XX_OFFSET = 70;
  private static final int BODY_WIDTH = 470;
  private static final float BODY_LINE_SPACING = PdfUtility.LINE_HEIGHT * 1.5f;

  private GeneratedPages() {
  }

  static void addTitlePage(PDDocument document, AssemblyRequest request, PdfFonts fonts)
      throws IOException {
    PDPage page = new PDPage();
    document.addPage(page);
    PdfUtility.addCenterText(document, page, request.bundleTitle(), 280,
        fonts.helveticaBold(), 14);
    if (request.description().isPresent()) {
      PdfUtility.addCenterText(document, page, request.description().get(), 330,
          fonts.helvetica(), 12);
    }
  }

  static void addEmptySectionPage(PDDocument document, String sectionTitle, PdfFonts fonts)
      throws IOException {
    PDPage page = new PDPage();
    document.addPage(page);
    PdfUtility.addCenterText(document, page, sectionTitle, 300, fonts.helveticaBold(), 14);
    PdfUtility.addCenterText(document, page, "There are no documents in this section.", 340,
        fonts.helvetica(), 12);
  }

  static void addMediaLinkPage(PDDocument document, String title, Optional<LocalDate> date,
      MediaLinkPage media, PdfFonts fonts) throws IOException {
    PDPage page = new PDPage();
    document.addPage(page);
    PdfUtility.addCenterText(document, page, title, 150, fonts.helveticaBold(), 14);

    float yyOffset = 230;
    if (date.isPresent()) {
      yyOffset = addBodyLine(document, page, "Date: " + DATE_FORMAT.format(date.get()),
          yyOffset, fonts);
    }
    yyOffset = addBodyLine(document, page, "Media type: " + media.mediaType(), yyOffset, fonts);
    if (media.placeholder().duration().isPresent()) {
      yyOffset = addBodyLine(document, page,
          "Duration: " + formatDuration(media.placeholder().duration().get()), yyOffset, fonts);
    }
    if (media.placeholder().note().isPresent()) {
      String note = media.placeholder().note().get();
      int noteLines =
          PdfUtility.splitString(note, BODY_WIDTH, fonts.helvetica(), 12).length;
      PdfUtility.addText(document, page, note, BODY_XX_OFFSET, yyOffset, fonts.helvetica(), 12,
          BODY_WIDTH);
      yyOffset += noteLines * BODY_LINE_SPACING;
    }
    yyOffset = addBodyLine(document, page, "This recording is not part of the printed bundle."
        + " Use the link below to access it.", yyOffset, fonts);
    PdfUtility.addUriLink(document, page, media.placeholder().accessUrl(),
        media.placeholder().accessUrl(), BODY_XX_OFFSET, yyOffset, fonts.helvetica(), 12,
        BODY_WIDTH);
  }

  private static float addBodyLine(PDDocument document, PDPage page, String text, float yyOffset,
      PdfFonts fonts) throws IOException {
    PdfUtility.addText(document, page, text, BODY_XX_OFFSET, yyOffset, fonts.helvetica(), 12,
        BODY_WIDTH);
    return yyOffset + BODY_LINE_SPACING;
  }

  private static String formatDuration(Duration duration) {
    long hours = duration.toHours();
    int minutes = duration.toMinutesPart();
    int seconds = duration.toSecondsPart();
    StringBuilder sb = new StringBuilder();
    if (hours > 0) {
      sb.append(hours).append("h ");
    }
    if (hours > 0 || minutes > 0) {
      sb.append(minutes).append("m ");
    }
    sb.append(seconds).append("s");
    return sb.toString();
  }
}
