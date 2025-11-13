package uk.gov.hmcts.reform.cftlib.pdf;

import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.error.PebbleException;
import com.mitchellbosecke.pebble.loader.StringLoader;
import com.mitchellbosecke.pebble.template.PebbleTemplate;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.reform.cftlib.pdf.exception.MalformedTemplateException;
import uk.gov.hmcts.reform.cftlib.pdf.exception.PdfGenerationException;

@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
class HtmlTemplateProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(HtmlTemplateProcessor.class);

  private final PebbleEngine pebble;

  public HtmlTemplateProcessor() {
    this(new PebbleEngine.Builder()
        .strictVariables(true)
        .loader(new StringLoader())
        .cacheActive(false)
        .build()
    );
  }

  public HtmlTemplateProcessor(PebbleEngine pebble) {
    this.pebble = pebble;
  }

  public static HtmlTemplateProcessor withDefaults(Consumer<PebbleEngine.Builder> builderCustomizer) {
    PebbleEngine.Builder builder = new PebbleEngine.Builder()
        .strictVariables(true)
        .loader(new StringLoader())
        .cacheActive(false);
    builderCustomizer.accept(builder);
    return new HtmlTemplateProcessor(builder.build());
  }

  /**
   * Processes a Twig template.
   *
   * @param template a string which contains the Twig template
   * @param context a map with a structure corresponding to the placeholders used in the template
   * @return a String containing processed HTML output
   */
  public String process(String template, Map<String, Object> context) {
    LOG.debug("Processing template");
    LOG.trace("Template: {}", template);
    LOG.trace("Context: {}", context);
    try (Writer writer = new StringWriter()) {
      PebbleTemplate pebbleTemplate = pebble.getTemplate(template);
      pebbleTemplate.evaluate(writer, context);
      LOG.debug("Template processing finished successfully");
      return writer.toString();
    } catch (PebbleException e) {
      throw new MalformedTemplateException("Malformed Twig syntax in the template", e);
    } catch (IOException e) {
      throw new PdfGenerationException("There was an error during template processing", e);
    }
  }
}
