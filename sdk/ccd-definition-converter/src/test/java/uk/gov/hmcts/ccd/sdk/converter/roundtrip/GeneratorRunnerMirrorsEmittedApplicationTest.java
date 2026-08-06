package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.javapoet.JavaFile;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.api.EmitContext;
import uk.gov.hmcts.ccd.sdk.converter.emit.config.ApplicationEmitter;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

/**
 * Pins {@link GeneratorRunner}'s Spring wiring to the entry point {@link ApplicationEmitter} emits.
 *
 * <p>The round-trip harness builds its own context instead of loading the emitted application class,
 * so the two can drift — and when they do, the round trip is a green test over a generated app that
 * does not start. That is not hypothetical: the harness scanned the root {@code uk.gov.hmcts.ccd.sdk}
 * package, which also holds the runtime callback beans and their {@code @Autowired ObjectMapper}.
 * While the emitted app was a {@code @SpringBootApplication}, autoconfiguration happened to supply
 * that bean; once it became a plain {@code @SpringBootConfiguration}, every real service run failed
 * with "No qualifying bean of type ObjectMapper" while the harness stayed green.
 *
 * <p>This is deliberately a plain unit test, not {@code round-trip}-tagged: it costs nothing and
 * catching the drift needs to be cheap.
 */
class GeneratorRunnerMirrorsEmittedApplicationTest {

  private static String emittedApplicationSource() {
    ConversionOptions opts = ConversionOptions.builder()
        .modelPackage("uk.gov.hmcts.test.model")
        .configPackage("uk.gov.hmcts.test.config")
        .eventsPerConfig(40)
        .emitApplication(true)
        .build();
    EmitContext context = EmitContext.builder()
        .options(opts)
        .gaps(new GapCollector())
        .build();
    CaseTypeModel model = CaseTypeModel.builder()
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
    List<JavaFile> files = new ApplicationEmitter().emit(model, context);
    return files.get(0).toString();
  }

  @Test
  void theHarnessScansTheSameSdkPackageTheEmittedApplicationDoes() {
    assertThat(emittedApplicationSource()).contains(GeneratorRunner.SDK_GENERATOR_PACKAGE);
  }

  @Test
  void theHarnessRegistersTheSameSdkConfigurationTheEmittedApplicationImports() {
    // The emitted source imports the class, so it refers to it by simple name.
    String fqn = GeneratorRunner.SDK_GENERATOR_CONFIG;
    String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
    assertThat(emittedApplicationSource()).contains("@Import(" + simpleName + ".class)");
  }

  @Test
  void neitherTheHarnessNorTheEmittedApplicationScansTheSdkRootPackage() {
    // The root package holds the runtime callback layer, whose ObjectMapper nothing supplies once
    // autoconfiguration is gone. Asserted on both sides because either one drifting hides the other.
    assertThat(GeneratorRunner.SDK_GENERATOR_PACKAGE).isNotEqualTo("uk.gov.hmcts.ccd.sdk");
    assertThat(emittedApplicationSource()).doesNotContain("\"uk.gov.hmcts.ccd.sdk\"");
  }
}
