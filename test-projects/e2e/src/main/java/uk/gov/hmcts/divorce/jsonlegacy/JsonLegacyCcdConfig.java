package uk.gov.hmcts.divorce.jsonlegacy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.json.JsonBackedCCDConfigFactory;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

@Configuration
public class JsonLegacyCcdConfig {

  private static final String JSON_ROOT = "classpath:json-ccd-definitions/json-legacy";

  @Bean
  CCDConfig<CaseData, State, UserRole> jsonLegacyConfig(JsonBackedCCDConfigFactory jsonBackedCCDConfigFactory) {
    return jsonBackedCCDConfigFactory.create(NoFaultDivorce.getCaseType(), JSON_ROOT);
  }
}
