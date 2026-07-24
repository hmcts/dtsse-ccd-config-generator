package uk.gov.hmcts.ccd.sdk.converter.emit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.javapoet.JavaFile;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.api.EmitContext;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

/**
 * Tests for {@link EnvironmentFlagsEmitter}.
 */
class EnvironmentFlagsEmitterTest {

  static final String MODEL_PKG = "uk.gov.hmcts.test.model";
  static final String CONFIG_PKG = "uk.gov.hmcts.test.config";

  static EmitContext context() {
    ConversionOptions opts = ConversionOptions.builder()
        .modelPackage(MODEL_PKG)
        .configPackage(CONFIG_PKG)
        .eventsPerConfig(40)
        .build();
    return EmitContext.builder()
        .options(opts)
        .gaps(new GapCollector())
        .build();
  }

  static CaseTypeModel minimalModel() {
    return CaseTypeModel.builder()
        .caseTypeId("Minimal")
        .caseTypeName("Minimal Case")
        .caseTypeDescription("Test")
        .jurisdictionId("TEST")
        .jurisdictionName("Test Jurisdiction")
        .jurisdictionDescription("Fixture jurisdiction")
        .states(List.of())
        .roles(List.of())
        .caseFields(List.of())
        .complexTypes(List.of())
        .fixedLists(List.of())
        .events(List.of())
        .tabs(List.of())
        .searchInputFields(List.of())
        .searchResultFields(List.of())
        .workBasketInputFields(List.of())
        .workBasketResultFields(List.of())
        .searchCasesResultFields(List.of())
        .stateAuthorisations(List.of())
        .accessClasses(List.of())
        .searchCriteria(List.of())
        .searchParties(List.of())
        .challengeQuestions(List.of())
        .roleToAccessProfiles(List.of())
        .categories(List.of())
        .passthroughSheets(List.of())
        .build();
  }

  @Test
  void emitsExactlyOneFile() {
    List<JavaFile> files = new EnvironmentFlagsEmitter().emit(minimalModel(), context());
    assertThat(files).hasSize(1);
  }

  @Test
  void generatedSourceContainsClassName() {
    String src = new EnvironmentFlagsEmitter().emit(minimalModel(), context())
        .get(0).toString();
    assertThat(src).contains("class EnvironmentFlags");
  }

  @Test
  void generatedSourceContainsCorrectPackage() {
    String src = new EnvironmentFlagsEmitter().emit(minimalModel(), context())
        .get(0).toString();
    assertThat(src).contains("package " + MODEL_PKG);
  }

  @Test
  void generatedSourceContainsFlagMethod() {
    String src = new EnvironmentFlagsEmitter().emit(minimalModel(), context())
        .get(0).toString();
    assertThat(src).contains("public static boolean flag(");
  }

  @Test
  void generatedSourceContainsPrivateConstructor() {
    String src = new EnvironmentFlagsEmitter().emit(minimalModel(), context())
        .get(0).toString();
    assertThat(src).contains("private EnvironmentFlags()");
  }

  @Test
  void generatedSourceChecksBothSystemPropertyAndEnv() {
    String src = new EnvironmentFlagsEmitter().emit(minimalModel(), context())
        .get(0).toString();
    assertThat(src).contains("System.getProperty(");
    assertThat(src).contains("System.getenv()");
  }

  @Test
  void generatedSourceUsesEqualsIgnoreCase() {
    String src = new EnvironmentFlagsEmitter().emit(minimalModel(), context())
        .get(0).toString();
    assertThat(src).contains("equalsIgnoreCase(");
  }

  @Test
  void goldenSourceMatchesExpected() throws Exception {
    String generated = new EnvironmentFlagsEmitter().emit(minimalModel(), context())
        .get(0).toString();

    java.net.URL resource = getClass().getClassLoader()
        .getResource("golden/config-emit/expected/EnvironmentFlags.java");
    assertThat(resource).isNotNull();
    String expected = java.nio.file.Files.readString(
        java.nio.file.Paths.get(resource.toURI()));

    assertThat(generated.strip()).isEqualTo(expected.strip());
  }
}
