package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

// Generated text is drawn in Liberation Sans (SIL OFL 1.1, licence alongside the font files),
// embedded as a subset. It is metric-compatible with Helvetica, so layout is unchanged, and
// unlike a standard-14 font it covers Welsh (ŵ ŷ Ŵ Ŷ) and other Latin Extended text.
// A Type 0 font is bound to the document it is loaded into: create one PdfFonts per PDDocument.
final class PdfFonts {
  private static final byte[] REGULAR = read("fonts/LiberationSans-Regular.ttf");
  private static final byte[] BOLD = read("fonts/LiberationSans-Bold.ttf");

  private final PDFont regular;
  private final PDFont bold;

  PdfFonts(PDDocument document) throws IOException {
    this.regular = PDType0Font.load(document, new ByteArrayInputStream(REGULAR), true);
    this.bold = PDType0Font.load(document, new ByteArrayInputStream(BOLD), true);
  }

  PDFont regular() {
    return regular;
  }

  PDFont bold() {
    return bold;
  }

  private static byte[] read(String resource) {
    try (InputStream in = PdfFonts.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing font resource " + resource);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
