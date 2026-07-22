package uk.gov.hmcts.ccd.sdk.retention;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Getter
@Setter
@ConfigurationProperties(prefix = RetainAndDisposeProperties.PREFIX)
public class RetainAndDisposeProperties {

  static final String PREFIX = "ccd.decentralised-runtime.retain-and-dispose";

  private Mode mode = Mode.OFF;
  private String cron = "0 0 2 * * *";
  private String zone = "UTC";
  private int maximumCandidatePercentage = 5;
  private int minimumCandidateCount = 10;
  private final SystemUser systemUser = new SystemUser();

  void validate() {
    if (!StringUtils.hasText(systemUser.username) || !StringUtils.hasText(systemUser.password)) {
      throw new IllegalStateException(
          PREFIX + ".system-user.username and " + PREFIX + ".system-user.password must be configured"
      );
    }
    if (maximumCandidatePercentage < 0 || maximumCandidatePercentage > 100) {
      throw new IllegalStateException(PREFIX + ".maximum-candidate-percentage must be between 0 and 100");
    }
    if (minimumCandidateCount < 1) {
      throw new IllegalStateException(PREFIX + ".minimum-candidate-count must be at least 1");
    }
  }

  public enum Mode {
    OFF,
    DRY_RUN,
    LIVE
  }

  @Getter
  @Setter
  public static class SystemUser {
    private String username;
    private String password;
  }
}
