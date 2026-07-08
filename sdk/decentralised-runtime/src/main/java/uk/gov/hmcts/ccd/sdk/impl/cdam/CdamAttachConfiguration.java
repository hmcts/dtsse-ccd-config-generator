package uk.gov.hmcts.ccd.sdk.impl.cdam;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "ccd.decentralised-runtime.cdam-attach", name = "enabled", havingValue = "true")
public class CdamAttachConfiguration {

  @Bean
  public CaseDocumentHashScanner caseDocumentHashScanner() {
    return new CaseDocumentHashScanner();
  }
}
