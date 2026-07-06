package uk.gov.hmcts.ccd.sdk.retention;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ccd.decentralised-runtime.retention")
public class RetentionProperties {
  private Disposal disposal = new Disposal();

  @Data
  public static class Disposal {
    private String caseTypeIds = "";
    private String simulationCaseTypeIds = "";
    private int batchSize = 100;
  }
}
