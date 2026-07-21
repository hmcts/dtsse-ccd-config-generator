package uk.gov.hmcts.ccd.sdk.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ccd.decentralised-runtime.retain-and-dispose")
public record RetainAndDisposeProperties(SystemUser systemUser) {

  public record SystemUser(String username, String password) {
  }
}
