package uk.gov.hmcts.ccd.sdk.servicebus;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@ConfigurationProperties(prefix = "ccd.servicebus")
public class CcdServiceBusProperties {

  @Setter
  private boolean schedulerEnabled = false;

  private final String messageType = "CASE_EVENT";

  @Setter
  private int batchSize = 1000;

  @Setter
  private int publishedRetentionDays = 90;

  @Setter
  private String schedule = "*/30 * * * * *";

  @Setter
  private String destination;
}
