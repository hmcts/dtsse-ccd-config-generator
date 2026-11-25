package uk.gov.hmcts.ccd.sdk.doctoolkit.pdf;

import java.io.IOException;
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

  static void addMissingDocumentPage(PDDocument document, String title, Optional<LocalDate> date,
      MissingDocumentPage missing, PdfFonts fonts) throws IOException {
    PDPage page = new PDPage();
    document.addPage(page);
    PdfUtility.addCenterText(document, page, title, 150, fonts.helveticaBold(), 14);
    PdfUtility.addCenterText(document, page, "This document is missing from the bundle", 190,
        fonts.helveticaBold(), 12);

    float yyOffset = 250;
    if (date.isPresent()) {
      yyOffset = addBodyLine(document, page, "Date: " + DATE_FORMAT.format(date.get()),
          yyOffset, fonts);
    }
    PdfUtility.addText(document, page, missing.reason(), BODY_XX_OFFSET, yyOffset,
        fonts.helvetica(), 12, BODY_WIDTH);
  }

  private static float addBodyLine(PDDocument document, PDPage page, String text, float yyOffset,
      PdfFonts fonts) throws IOException {
    PdfUtility.addText(document, page, text, BODY_XX_OFFSET, yyOffset, fonts.helvetica(), 12,
        BODY_WIDTH);
    return yyOffset + BODY_LINE_SPACING;
  }
}
