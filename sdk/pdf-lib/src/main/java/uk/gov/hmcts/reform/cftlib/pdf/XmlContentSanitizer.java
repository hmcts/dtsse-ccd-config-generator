package uk.gov.hmcts.reform.cftlib.pdf;

@SuppressWarnings("checkstyle:IllegalTokenText")
class XmlContentSanitizer {

  private static final String INVALID_XML_CHARS = "[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD]";

  public String stripIllegalCharacters(String input) {
    return input.replaceAll(INVALID_XML_CHARS, "");
  }
}
