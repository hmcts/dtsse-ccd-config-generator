package uk.gov.hmcts.divorce.jsonlegacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.impl.json.JsonCallbackAdapterFactory;
import uk.gov.hmcts.ccd.sdk.json.JsonBackedCCDConfig;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

@Configuration
public class JsonLegacyCcdConfig {

  public static final String CASE_TYPE = "case-type-a";
  private static final String JSON_ROOT = "classpath:json-ccd-definitions/CaseTypeA";

  @Bean
  CCDConfig<E2eJson, State, UserRole> jsonLegacyConfig(ResourceLoader resourceLoader,
                                                        ObjectMapper mapper,
                                                        JsonCallbackAdapterFactory callbackAdapterFactory) {
    return new JsonBackedCCDConfig<>(
      CASE_TYPE,
      JSON_ROOT,
      resourceLoader,
      mapper,
      callbackAdapterFactory
    ) {
    };
  }
}
