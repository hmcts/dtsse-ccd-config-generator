package uk.gov.hmcts.ccd.sdk.converter.emit.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Names}.
 */
class NamesTest {

  @Test
  void toClassNameHandlesSimpleId() {
    assertThat(Names.toClassName("Minimal")).isEqualTo("Minimal");
  }

  @Test
  void toClassNameConvertsHyphenatedId() {
    assertThat(Names.toClassName("my-case-type")).isEqualTo("MyCaseType");
  }

  @Test
  void toClassNameHandlesUnderscoreSeparatedId() {
    assertThat(Names.toClassName("MY_CASE")).isEqualTo("MYCASE");
  }

  @Test
  void toClassNameReturnsUnknownForNull() {
    assertThat(Names.toClassName(null)).isEqualTo("Unknown");
  }

  @Test
  void toClassNameReturnsUnknownForEmptyString() {
    assertThat(Names.toClassName("")).isEqualTo("Unknown");
  }

  @Test
  void toMethodNameHandlesSimpleId() {
    assertThat(Names.toMethodName("createCase")).isEqualTo("createCase");
  }

  @Test
  void toMethodNameReplacesHyphens() {
    assertThat(Names.toMethodName("create-case")).isEqualTo("create_case");
  }

  @Test
  void toMethodNamePrependsEventForLeadingDigit() {
    assertThat(Names.toMethodName("1abc")).isEqualTo("event1abc");
  }

  @Test
  void toMethodNameReturnsUnknownForNull() {
    assertThat(Names.toMethodName(null)).isEqualTo("unknown");
  }
}
