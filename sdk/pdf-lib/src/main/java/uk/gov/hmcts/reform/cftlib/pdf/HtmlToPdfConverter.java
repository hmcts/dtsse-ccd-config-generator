package uk.gov.hmcts.reform.cftlib.pdf;

import java.util.Map;
import java.util.Objects;

@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
class HtmlToPdfConverter {

  private final HtmlTemplateProcessor templateProcessor;
  private final PdfGenerator pdfGenerator;
  private final XmlContentSanitizer xmlContentSanitizer;

  public HtmlToPdfConverter() {
    this(new HtmlTemplateProcessor(), new PdfGenerator(), new XmlContentSanitizer());
  }

  public HtmlToPdfConverter(
      HtmlTemplateProcessor templateProcessor,
      PdfGenerator pdfGenerator,
      XmlContentSanitizer xmlContentSanitizer
  ) {
    this.templateProcessor = Objects.requireNonNull(templateProcessor);
    this.pdfGenerator = Objects.requireNonNull(pdfGenerator);
    this.xmlContentSanitizer = Objects.requireNonNull(xmlContentSanitizer);
  }

  /**
   * Generates a PDF document from provided Twig/HTML template and placeholder values.
   *
   * @param template a string which contains the Twig template
   * @param context a map with a structure corresponding to the placeholders used in the template
   * @return a byte array which contains generated PDF output
   */
  public byte[] convert(String template, Map<String, Object> context) {
    String processedHtml = templateProcessor.process(template, context);
    return pdfGenerator.generateFrom(xmlContentSanitizer.stripIllegalCharacters(processedHtml));
  }
}
