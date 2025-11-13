package uk.gov.hmcts.reform.cftlib.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PdfServiceTest {

  @Test
  void shouldGeneratePdfFromTemplateAndValues() {
    PdfService service = PdfService.createDefault();
    String template = """
        <html>
          <body>
            <h1>Hello {{ name }}</h1>
          </body>
        </html>
        """;

    byte[] result = service.generate(template, Map.of("name", "Tribunal"));

    assertThat(result).isNotEmpty();
  }

  @Test
  void shouldSupportByteArrayTemplates() {
    PdfService service = PdfService.createDefault();
    byte[] template = """
        <html>
          <body>
            <p>{{ content }}</p>
          </body>
        </html>
        """.getBytes();

    byte[] result = service.generate(template, Map.of("content", "byte-array"));

    assertThat(result).isNotEmpty();
  }
}
