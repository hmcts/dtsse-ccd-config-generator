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
  void generatedClassHasSpringBootApplicationAnnotation() {
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).contains("@SpringBootApplication");
  }

  @Test
  void scansSdkAndConfigPackagesSoTheGeneratorResolvesItsBeans() {
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).contains("scanBasePackages");
    assertThat(src).contains("uk.gov.hmcts.ccd.sdk");
    assertThat(src).contains(EnvironmentFlagsEmitterTest.CONFIG_PKG);
  }

  @Test
  void doesNotComponentScanTheModelPackage() {
    // Retrofit mode points modelPackage at the real service package, full of @Component/@Service
    // beans whose dependencies are not on the generator's scan path; scanning it fails the context.
    // The model classes are reflected by type, not Spring-wired, so they need no scan.
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    int scanStart = src.indexOf("scanBasePackages");
    int scanEnd = src.indexOf('}', scanStart);
    String scanMember = src.substring(scanStart, scanEnd);
    assertThat(scanMember).doesNotContain(EnvironmentFlagsEmitterTest.MODEL_PKG);
  }

  @Test
  void excludesPersistenceAutoConfigurationsSoNoDatabaseIsRequired() {
    // generateCCDConfig never touches a database, but in retrofit mode the generated app runs on the
    // service's classpath (JPA/Flyway/JDBC driver + application.yaml); without excluding these the
    // context stands up a DataSource/Flyway migrator and fails with no database to connect to.
    String src = new ApplicationEmitter()
        .emit(EnvironmentFlagsEmitterTest.minimalModel(), contextWithApplicationEmit(true))
        .get(0).toString();
    assertThat(src).contains("excludeName");
    assertThat(src).contains("DataSourceAutoConfiguration");
    assertThat(src).contains("HibernateJpaAutoConfiguration");
    assertThat(src).contains("FlywayAutoConfiguration");
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
