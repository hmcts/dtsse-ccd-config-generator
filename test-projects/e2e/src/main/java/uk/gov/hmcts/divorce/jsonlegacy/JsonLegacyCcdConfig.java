package uk.gov.hmcts.divorce.jsonlegacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Path;
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

  public static final String CASE_TYPE_A = "case-type-a";
  public static final String CASE_TYPE_B = "case-type-b";
  private static final Path JSON_LEGACY_DEFINITIONS_PATH = Path.of("build/json-ccd-definitions");
  private static final Path CASE_TYPE_A_JSON_PATH = JSON_LEGACY_DEFINITIONS_PATH.resolve("CaseTypeA");
  private static final Path CASE_TYPE_B_JSON_PATH = JSON_LEGACY_DEFINITIONS_PATH.resolve("CaseTypeB");
  private static final String CASE_TYPE_A_JSON_ROOT = CASE_TYPE_A_JSON_PATH.toUri().toString();
  private static final String CASE_TYPE_B_JSON_ROOT = CASE_TYPE_B_JSON_PATH.toUri().toString();

  public static File caseTypeADefinitionDirectory() {
    return CASE_TYPE_A_JSON_PATH.toFile();
  }

  public static File caseTypeBDefinitionDirectory() {
    return CASE_TYPE_B_JSON_PATH.toFile();
  }

  @Bean
  CCDConfig<E2eJson, State, UserRole> jsonLegacyCaseTypeAConfig(ResourceLoader resourceLoader,
                                                                 ObjectMapper mapper,
                                                                 JsonCallbackAdapterFactory callbackAdapterFactory) {
    return new JsonBackedCCDConfig<E2eJson, State, UserRole>(
      CASE_TYPE_A,
      CASE_TYPE_A_JSON_ROOT,
      resourceLoader,
      mapper,
      callbackAdapterFactory
    ) {
    };
  }

  @Bean
  CCDConfig<E2eJsonB, State, UserRole> jsonLegacyCaseTypeBConfig(ResourceLoader resourceLoader,
                                                                  ObjectMapper mapper,
                                                                  JsonCallbackAdapterFactory callbackAdapterFactory) {
    return new JsonBackedCCDConfig<E2eJsonB, State, UserRole>(
      CASE_TYPE_B,
      CASE_TYPE_B_JSON_ROOT,
      resourceLoader,
      mapper,
      callbackAdapterFactory
    ) {
    };
  }
}
