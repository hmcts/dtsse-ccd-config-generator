package uk.gov.hmcts.reform.cftlib.pdf;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
class HtmlToPdfConverterTest {

  private final HtmlToPdfConverter converter = new HtmlToPdfConverter();

  @Test
  void shouldSuccessfullyProcessContentWithIllegalCharacters() {
    String templateWithIllegalCharacters = ResourceLoader.loadString("/illegal-characters.html");

    byte[] output = converter.convert(templateWithIllegalCharacters, emptyMap());

    assertThat(output).isNotEmpty();
  }
}
