package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

final class GeneratedPages {
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
}
