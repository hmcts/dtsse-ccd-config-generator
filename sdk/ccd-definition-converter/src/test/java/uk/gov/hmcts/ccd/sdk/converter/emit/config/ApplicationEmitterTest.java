package uk.gov.hmcts.ccd.sdk.converter.emit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.javapoet.JavaFile;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.api.EmitContext;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

/**
 * Tests for {@link ApplicationEmitter}.
 */
class ApplicationEmitterTest {

  private static EmitContext contextWithApplicationEmit(boolean emitApp) {
    ConversionOptions opts = ConversionOptions.builder()
        .modelPackage(EnvironmentFlagsEmitterTest.MODEL_PKG)
        .configPackage(EnvironmentFlagsEmitterTest.CONFIG_PKG)
        .eventsPerConfig(40)
        .emitApplication(emitApp)
        .build();
    return EmitContext.builder()
        .options(opts)
        .gaps(new GapCollector())
        .build();
  }

  @Test
  void emitsNoFilesWhenOptionIsDisabled() {
    List<JavaFile> files = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(false));
    assertThat(files).isEmpty();
  }

  @Test
  void emitsOneFileWhenOptionIsEnabled() {
    List<JavaFile> files = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true));
    assertThat(files).hasSize(1);
  }

  @Test
  void generatedClassIsNamedConverterGeneratedApplication() {
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).contains("class ConverterGeneratedApplication");
  }

  @Test
  void generatedClassIsInConfigPackage() {
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).contains("package " + EnvironmentFlagsEmitterTest.CONFIG_PKG);
  }

  @Test
  void generatedClassIsASpringBootConfigurationNotAFullApplication() {
    // @SpringBootConfiguration + @ComponentScan, deliberately WITHOUT @EnableAutoConfiguration:
    // Main locates the entry point by @SpringBootConfiguration (which @SpringBootApplication is
    // meta-annotated with), so this still boots.
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).contains("@SpringBootConfiguration");
    assertThat(src).contains("@ComponentScan");
    assertThat(src).doesNotContain("@SpringBootApplication");
    assertThat(src).doesNotContain("EnableAutoConfiguration");
  }

  @Test
  void scansSdkGeneratorAndConfigPackagesSoTheGeneratorResolvesItsBeans() {
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).contains("basePackages");
    assertThat(src).contains("uk.gov.hmcts.ccd.sdk.generator");
    assertThat(src).contains(EnvironmentFlagsEmitterTest.CONFIG_PKG);
  }

  @Test
  void doesNotComponentScanTheSdkRootPackageWhichHoldsTheRuntimeCallbackBeans() {
    // The root uk.gov.hmcts.ccd.sdk package also holds the runtime callback layer
    // (CallbackController, CcdCallbackExecutor), whose constructor takes an @Autowired ObjectMapper.
    // With no autoconfiguration there is no Jackson bean to satisfy it, so scanning the root package
    // failed the context on probate. Those beans serve live callback traffic, not generation.
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).doesNotContain("\"uk.gov.hmcts.ccd.sdk\"");
  }

  @Test
  void importsTheDefinitionGeneratorByTypeSinceItLivesInTheUnscannedRootPackage() {
    // CCDDefinitionGenerator is a @Configuration in the root sdk package, so narrowing the scan to
    // .generator would lose it — @Import registers exactly that one class without its neighbours.
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).contains("@Import(CCDDefinitionGenerator.class)");
  }

  @Test
  void doesNotComponentScanTheModelPackage() {
    // Retrofit mode points modelPackage at the real service package, full of @Component/@Service
    // beans whose dependencies are not on the generator's scan path; scanning it fails the context.
    // The model classes are reflected by type, not Spring-wired, so they need no scan.
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    int scanStart = src.indexOf("basePackages");
    int scanEnd = src.indexOf('}', scanStart);
    String scanMember = src.substring(scanStart, scanEnd);
    assertThat(scanMember).doesNotContain(EnvironmentFlagsEmitterTest.MODEL_PKG);
  }

  @Test
  void importsNoAutoConfigurationRatherThanExcludingTheHarmfulOnesByName() {
    // generateCCDConfig needs only the CCDConfig beans and the CCDDefinitionGenerator, neither of
    // which requires any infrastructure — the round-trip harness runs all seven fixtures through a
    // bare component-scanning context with no Boot at all. In retrofit mode this class runs on the
    // real service's classpath, where autoconfiguration is pure liability: an exclude blocklist had to
    // grow for each new service (DataSource/Flyway on Civil, OAuth2 on several, then probate's
    // third-party lifeevents client demanding the very ClientRegistrationRepository that excluding
    // OAuth2 removed) and naming a third-party class is itself unsafe (Spring fails the context when
    // an excluded class is present but is not an autoconfiguration candidate — version-dependent).
    // Importing nothing removes the failure class instead of its current instance.
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).doesNotContain("excludeName");
    assertThat(src).doesNotContain("AutoConfiguration");
  }

  @Test
  void scansTheConfigPackageForCcdConfigTypesOnlyNotForWhateverElseSharesThePackage() {
    // Naming a package does not bound what a scan instantiates, and the companion root package is not
    // always exclusively generated: sscs-common declares its model at ...sscs.ccd.domain, so the
    // derived root is ...sscs.ccd — which also holds the library's own ...sscs.ccd.client.CcdClient.
    // An unfiltered scan created that bean and failed the context on its CcdRequestDetails constructor
    // parameter, config a generation run has no reason to supply. Filtering by type instead makes the
    // scan pick up exactly what generation consumes whatever else lives alongside it.
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).contains("useDefaultFilters = false");
    assertThat(src).contains("FilterType.ASSIGNABLE_TYPE");
    assertThat(src).contains("CCDConfig.class");
    // The SDK generator scan must stay unfiltered — its beans are the sheet writers, not CCDConfigs.
    assertThat(src).contains("@ComponentScan(basePackages = \"uk.gov.hmcts.ccd.sdk.generator\")");
  }

  @Test
  void generatedClassHasMainMethod() {
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).contains("public static void main(String[] args)");
  }

  @Test
  void mainMethodCallsSpringApplicationRun() {
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).contains("SpringApplication.run(");
  }
}
