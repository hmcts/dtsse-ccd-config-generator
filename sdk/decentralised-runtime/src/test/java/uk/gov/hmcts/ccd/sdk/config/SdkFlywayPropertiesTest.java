package uk.gov.hmcts.ccd.sdk.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SdkFlywayPropertiesTest {

  @Test
  void rejectsUnsafeReaderRole() {
    var properties = new SdkFlywayProperties();

    assertThatThrownBy(() -> properties.setReaderRole("reader'; select 1; --"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ccd.sdk.flyway.reader-role");
  }
}
