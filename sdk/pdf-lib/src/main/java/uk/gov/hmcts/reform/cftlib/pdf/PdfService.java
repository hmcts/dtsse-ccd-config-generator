package uk.gov.hmcts.reform.cftlib.pdf;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

public final class PdfService {

  private final HtmlToPdfConverter converter;

  private PdfService(HtmlToPdfConverter converter) {
    this.converter = converter;
  }

  public static PdfService createDefault() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public byte[] generate(String template, Map<String, Object> context) {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(context, "context");
    return converter.convert(template, context);
  }

  public byte[] generate(byte[] template, Map<String, Object> context) {
    Objects.requireNonNull(template, "template");
    return generate(new String(template, StandardCharsets.UTF_8), context);
  }

  public static final class Builder {
    private HtmlTemplateProcessor templateProcessor = new HtmlTemplateProcessor();
    private PdfGenerator generator = new PdfGenerator();
    private XmlContentSanitizer sanitizer = new XmlContentSanitizer();

    private Builder() {
    }

    public Builder templateProcessor(HtmlTemplateProcessor processor) {
      this.templateProcessor = processor;
      return this;
    }

    public Builder pdfGenerator(PdfGenerator pdfGenerator) {
      this.generator = pdfGenerator;
      return this;
    }

    public Builder xmlContentSanitizer(XmlContentSanitizer xmlSanitizer) {
      this.sanitizer = xmlSanitizer;
      return this;
    }

    public PdfService build() {
      return new PdfService(new HtmlToPdfConverter(templateProcessor, generator, sanitizer));
    }
  }
}
