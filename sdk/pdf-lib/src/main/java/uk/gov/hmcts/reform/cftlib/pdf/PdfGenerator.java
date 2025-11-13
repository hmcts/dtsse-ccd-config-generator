package uk.gov.hmcts.reform.cftlib.pdf;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXParseException;
import uk.gov.hmcts.reform.cftlib.pdf.exception.MalformedTemplateException;
import uk.gov.hmcts.reform.cftlib.pdf.exception.PdfGenerationException;

class PdfGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(PdfGenerator.class);
  private static final String DEFAULT_FONT_PATH = "/OpenSans-Regular.ttf";
  private static final String DEFAULT_FONT_FAMILY = "Open Sans";

  /**
   * Generates a PDF document from provided HTML.
   *
   * @param htmlString a String containing HTML to convert to PDF
   * @return a byte array which contains generated PDF output
   */
  public byte[] generateFrom(final String htmlString) {
    LOG.debug("Generating PDF from given HTML file");
    LOG.trace("HTML content: {}", htmlString);
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      PdfRendererBuilder builder = new PdfRendererBuilder();
      builder.withHtmlContent(htmlString, null);
      builder.useFastMode();
      builder.toStream(outputStream);
      builder.useFont(
          PdfGenerator::loadDefaultFont,
          DEFAULT_FONT_FAMILY,
          400,
          BaseRendererBuilder.FontStyle.NORMAL,
          true);
      builder.run();
      LOG.debug("PDF generation finished successfully");
      return outputStream.toByteArray();
    } catch (Exception e) {
      if (hasCause(e, SAXParseException.class)) {
        throw new MalformedTemplateException("Malformed HTML document provided", e);
      }
      throw new PdfGenerationException("There was an error during PDF generation", e);
    }
  }

  private static InputStream loadDefaultFont() {
    InputStream stream = PdfGenerator.class.getResourceAsStream(DEFAULT_FONT_PATH);
    if (stream == null) {
      throw new PdfGenerationException(
          "Failed to load default font resource: " + DEFAULT_FONT_PATH,
          new IllegalStateException("Font resource missing"));
    }
    return stream;
  }

  private static boolean hasCause(Throwable throwable, Class<?> type) {
    Throwable cursor = throwable;
    while (cursor != null) {
      if (type.isInstance(cursor)) {
        return true;
      }
      cursor = cursor.getCause();
    }
    return false;
  }
}
