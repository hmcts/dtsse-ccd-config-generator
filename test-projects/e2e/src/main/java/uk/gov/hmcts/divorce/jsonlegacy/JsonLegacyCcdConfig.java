package uk.gov.hmcts.divorce.jsonlegacy;

import java.io.File;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.json.JsonBackedCCDConfig;
import uk.gov.hmcts.ccd.sdk.json.JsonCCDConfigSupport;
import uk.gov.hmcts.divorce.divorcecase.model.State;

@Configuration
public class JsonLegacyCcdConfig {

  public static final String CASE_TYPE_A = "case-type-a";
  public static final String CASE_TYPE_B = "case-type-b";
  private static final Path JSON_LEGACY_DEFINITIONS_PATH = Path.of("build/json-ccd-definitions");
  private static final Path CASE_TYPE_A_JSON_PATH = JSON_LEGACY_DEFINITIONS_PATH.resolve("CaseTypeA");
  private static final Path CASE_TYPE_B_JSON_PATH = JSON_LEGACY_DEFINITIONS_PATH.resolve("CaseTypeB");

  public static File caseTypeADefinitionDirectory() {
    return CASE_TYPE_A_JSON_PATH.toFile();
  }

  public static File caseTypeBDefinitionDirectory() {
    return CASE_TYPE_B_JSON_PATH.toFile();
  }

  @Bean
  CCDConfig<LegacyJsonDataModel, State, JsonOnlyRole> jsonLegacyCaseTypeAConfig(
      JsonCCDConfigSupport support) {
    return new JsonBackedCCDConfig<LegacyJsonDataModel, State, JsonOnlyRole>(
        support,
        CASE_TYPE_A,
        CASE_TYPE_A_JSON_PATH.toUri().toString()
    ) {};
  }

  @Bean
  CCDConfig<LegacyJsonDataModel, State, JsonOnlyRole> jsonLegacyCaseTypeBConfig(
      JsonCCDConfigSupport support) {
    return new JsonBackedCCDConfig<LegacyJsonDataModel, State, JsonOnlyRole>(
        support,
        CASE_TYPE_B,
        CASE_TYPE_B_JSON_PATH.toUri().toString()
    ) {};
  }

  enum JsonOnlyRole implements HasRole {
    ;

    @Override
    public String getRole() {
      return "";
    }

    @Override
    public String getCaseTypePermissions() {
      return "";
    }
  }
}
