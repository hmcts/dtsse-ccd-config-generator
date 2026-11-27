package uk.gov.hmcts.ccd.sdk.doctoolkit.convert;

import com.openhtmltopdf.extend.FSUriResolver;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;

/**
 * Renders a service-written HTML page to PDF, in-process. Nothing is fetched: any resource that
 * is not a {@code data:} URI resolves to nothing, so a page cannot be made to reach the network
 * or the local file system.
 */
public final class HtmlToPdf {
  private static final String PAGE_CSS = "@page { size: A4; margin: 20mm; }"
      + " body { font-family: sans-serif; font-size: 11pt; }";

  private HtmlToPdf() {
  }

  /**
   * Writes {@code html} to {@code output} as PDF.
   *
   * @param title the PDF's title, or null
   * @param css extra CSS applied after the defaults, or null
   */
  public static void render(String title, String html, String css, Path output)
      throws ConversionException {
    Document document = Jsoup.parse(html);
    document.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
    document.head().appendElement("style").text(PAGE_CSS + (css == null ? "" : " " + css));
    if (title != null && !title.isBlank() && document.title().isBlank()) {
      document.title(title);
    }
    try (OutputStream out = Files.newOutputStream(output)) {
      PdfRendererBuilder builder = new PdfRendererBuilder();
      builder.useFastMode();
      builder.useUriResolver(NOTHING_EXTERNAL);
      builder.withW3cDocument(new W3CDom().fromJsoup(document), null);
      builder.toStream(out);
      builder.run();
    } catch (IOException | RuntimeException e) {
      throw new ConversionException(ConversionException.Reason.INVALID_CONTENT,
          (title == null ? "An HTML page" : title) + " could not be rendered: "
              + e.getClass().getSimpleName(), e);
    }
  }

  /**
   * Resolves {@code data:} URIs only; anything else is treated as missing.
   */
  private static final FSUriResolver NOTHING_EXTERNAL = (baseUri, uri) ->
      uri != null && uri.startsWith("data:") ? uri : null;
}
