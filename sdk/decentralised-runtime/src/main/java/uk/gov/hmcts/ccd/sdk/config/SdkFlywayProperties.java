package uk.gov.hmcts.ccd.sdk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ccd.sdk.flyway")
public class SdkFlywayProperties {

  private String readerRole = "DTS JIT Access ccd DB Reader SC";
}
