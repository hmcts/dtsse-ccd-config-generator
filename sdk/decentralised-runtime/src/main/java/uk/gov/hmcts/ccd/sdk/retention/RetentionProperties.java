package uk.gov.hmcts.ccd.sdk.retention;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ccd.decentralised-runtime.retention")
public class RetentionProperties {
  private Disposal disposal = new Disposal();
  private SystemUser systemUser = new SystemUser();

  @Data
  public static class Disposal {
    private String caseTypeIds = "";
    private String simulationCaseTypeIds = "";
    private int batchSize = 100;
  }

  @Data
  public static class SystemUser {
    private String username = "";
    private String password = "";
  }
}
