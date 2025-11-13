package uk.gov.hmcts.reform.cftlib.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@SuppressWarnings({
    "checkstyle:AvoidEscapedUnicodeCharacters",
    "checkstyle:AbbreviationAsWordInName",
    "checkstyle:IllegalTokenText"
})
class XmlContentSanitizerTest {

  private final XmlContentSanitizer sanitizer = new XmlContentSanitizer();

  @Test
  void shouldReturnValidStringUnmodified() {
    String input = "Valid string";
    String output = sanitizer.stripIllegalCharacters(input);
    assertThat(output).isEqualTo(input);
  }

  @Test
  void shouldStripAnIllegalFormFeedCharacterAndKeepTheRestOfInput() {
    String input = "abc\u000Cdef";
    String expected = "abcdef";
    String output = sanitizer.stripIllegalCharacters(input);
    assertThat(output).isEqualTo(expected);
  }

  @Test
  void shouldKeepAllowedControlCharacters() {
    String input = "\u0009\n\r";
    String output = sanitizer.stripIllegalCharacters(input);
    assertThat(output).isEqualTo(input);
  }

  @Test
  void shouldKeepAllowedCharacterFromBasicMultilingualPlaneEdgesBeforeSurrogateBlocks() {
    String input = "\u0020\uD7FF";
    String output = sanitizer.stripIllegalCharacters(input);
    assertThat(output).isEqualTo(input);
  }

  @Test
  void shouldKeepAllowedCharacterFromBasicMultilingualPlaneEdgesAfterSurrogateBlocks() {
    String input = "\uE000\uFFFD";
    String output = sanitizer.stripIllegalCharacters(input);
    assertThat(output).isEqualTo(input);
  }

  @Test
  void shouldStripSurrogateBlockCodePoint() {
    String input = "\uD800";
    String output = sanitizer.stripIllegalCharacters(input);
    assertThat(output).isEqualTo("");
  }

  @Test
  void shouldStripFffeAndFfffSpecialCharacters() {
    String input = "\uFFFE\uFFFF";
    String output = sanitizer.stripIllegalCharacters(input);
    assertThat(output).isEqualTo("");
  }
}
