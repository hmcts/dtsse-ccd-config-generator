package uk.gov.hmcts.divorce.jsonlegacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.impl.json.JsonCallbackAdapterFactory;
import uk.gov.hmcts.ccd.sdk.json.JsonBackedCCDConfig;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

@Configuration
public class JsonLegacyCcdConfig {

  private static final String JSON_ROOT = "classpath:json-ccd-definitions/json-legacy";

  @Bean
  CCDConfig<CaseData, State, UserRole> jsonLegacyConfig(ResourceLoader resourceLoader,
                                                        ObjectMapper mapper,
                                                        JsonCallbackAdapterFactory callbackAdapterFactory) {
    return new JsonBackedCCDConfig<CaseData, State, UserRole>(
        NoFaultDivorce.getCaseType(),
        JSON_ROOT,
        resourceLoader,
        mapper,
        callbackAdapterFactory
    ) {};
  }
}
