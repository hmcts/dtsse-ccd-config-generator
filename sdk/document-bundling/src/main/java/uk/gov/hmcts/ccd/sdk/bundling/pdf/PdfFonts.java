package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * The standard-14 fonts used by one assembly, created per {@code assemble()} call.
 *
 * <p>Per-assembly instances are deliberate: {@link PDType1Font} caches glyph encodings in a
 * plain {@code HashMap} that is mutated on every {@code showText}, so a static shared instance
 * would make concurrent assemblies race silently. The font metrics are identical across
 * instances, so line wrapping and page estimates are unaffected.
 */
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
