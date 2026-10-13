package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

final class PdfFonts {
  private final PDType1Font helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
  private final PDType1Font helveticaBold =
      new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

  PDType1Font helvetica() {
    return helvetica;
  }

  PDType1Font helveticaBold() {
    return helveticaBold;
  }
}
