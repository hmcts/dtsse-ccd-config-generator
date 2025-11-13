package uk.gov.hmcts.reform.cftlib.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.cftlib.pdf.exception.MalformedTemplateException;
import uk.gov.hmcts.reform.cftlib.pdf.exception.MalformedTemplateException;

@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
class PdfGeneratorTest {

  private final PdfGenerator pdfGenerator = new PdfGenerator();
  private final XmlContentSanitizer sanitizer = new XmlContentSanitizer();

  @Test
  void shouldThrowSaxParseExceptionWhenGivenHtmlWithIllegalCharacters() {
    String illegalHTML = ResourceLoader.loadString("/illegal-characters.html");

    Throwable thrown = catchThrowable(() -> pdfGenerator.generateFrom(illegalHTML));

    assertThat(thrown).isInstanceOf(MalformedTemplateException.class);
  }

  @Test
  void shouldProcessSuccessfullyAfterRunningIllegalHtmlThroughSanitizer() {
    String illegalHTML = ResourceLoader.loadString("/illegal-characters.html");

    Throwable thrown =
        catchThrowable(() -> pdfGenerator.generateFrom(sanitizer.stripIllegalCharacters(illegalHTML)));

    assertThat(thrown).isNull();
  }
}
