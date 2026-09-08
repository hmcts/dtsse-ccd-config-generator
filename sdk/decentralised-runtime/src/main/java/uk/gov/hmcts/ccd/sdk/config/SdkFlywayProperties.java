package uk.gov.hmcts.ccd.sdk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ccd.sdk.flyway")
public class SdkFlywayProperties {

  private String readerRole = "DTS JIT Access ccd DB Reader SC";

  public void setReaderRole(String readerRole) {
    if (readerRole == null || !readerRole.matches("[A-Za-z0-9 _-]+")) {
      throw new IllegalArgumentException(
          "ccd.sdk.flyway.reader-role may contain only letters, numbers, spaces, underscores, and hyphens");
    }
    this.readerRole = readerRole;
  }
}
