package uk.gov.hmcts.divorce.jsonlegacy;

import java.io.File;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.json.JsonCaseType;
import uk.gov.hmcts.ccd.sdk.json.JsonCaseTypeFactory;
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
  JsonCaseType<LegacyJsonDataModel, State> jsonLegacyCaseTypeAConfig(JsonCaseTypeFactory builder) {
    return builder.build(LegacyJsonDataModel.class, State.class, CASE_TYPE_A, CASE_TYPE_A_JSON_PATH.toUri().toString());
  }

  @Bean
  JsonCaseType<LegacyJsonDataModel, State> jsonLegacyCaseTypeBConfig(JsonCaseTypeFactory builder) {
    return builder.build(LegacyJsonDataModel.class, State.class, CASE_TYPE_B, CASE_TYPE_B_JSON_PATH.toUri().toString());
  }
}
