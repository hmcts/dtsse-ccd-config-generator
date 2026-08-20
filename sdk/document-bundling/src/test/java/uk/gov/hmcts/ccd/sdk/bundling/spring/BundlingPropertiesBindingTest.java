package uk.gov.hmcts.ccd.sdk.bundling.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDestination;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.testing.FilesystemBundleDestination;
import uk.gov.hmcts.reform.ccd.document.am.model.Classification;

/**
 * Property binding: kebab-case keys, environment-variable-style keys (the {@code DOCMOSIS_*} /
 * {@code TORNADO_*} vars services already hold bind through the yaml shown in the design doc),
 * per-field limit overrides, and the guarantee that the access key never appears in a bound
 * bean's {@code toString()}.
 */
class BundlingPropertiesBindingTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(BundlingAutoConfiguration.class));

  @Configuration
  static class RendererDependenciesConfig {
    @Bean
    DocumentResolver caseDocumentsResolver() {
      return new SpringWiringFixtures.FixturePdfResolver("case-documents");
    }

    @Bean
    BundleDestination filesystemDestination() {
      return new FilesystemBundleDestination(
          Path.of(System.getProperty("java.io.tmpdir"), "bundling-properties-binding-test"));
    }
  }

  @Test
  void kebabCasePropertiesBind() {
    // The temp directory is probed (created) at startup, so it must be a writable location.
    Path tempDirectory =
        Path.of(System.getProperty("java.io.tmpdir"), "bundling-binding-test-temp");
    runner.withUserConfiguration(RendererDependenciesConfig.class)
        .withPropertyValues(
            "ccd.bundling.max-concurrent-renders=4",
            "ccd.bundling.temp-directory=" + tempDirectory,
            "ccd.bundling.docmosis.convert-endpoint=https://docmosis.example/rs/convert",
            "ccd.bundling.docmosis.render-endpoint=https://docmosis.example/rs/render",
            "ccd.bundling.docmosis.access-key=test-access-key",
            "ccd.bundling.docmosis.connect-timeout=15s",
            "ccd.bundling.docmosis.read-timeout=2m",
            "ccd.bundling.docmosis.max-source-bytes=1048576",
            "ccd.bundling.docmosis.retry-attempts=3",
            "ccd.bundling.cdam.jurisdiction-id=DIVORCE",
            "ccd.bundling.cdam.case-type-id=NFD",
            "ccd.bundling.cdam.classification=RESTRICTED")
        .run(context -> {
          BundlingProperties properties = context.getBean(BundlingProperties.class);
          assertThat(properties.isEnabled()).isTrue();
          assertThat(properties.getMaxConcurrentRenders()).isEqualTo(4);
          assertThat(properties.getTempDirectory()).isEqualTo(tempDirectory);
          assertThat(properties.getDocmosis().getConvertEndpoint())
              .isEqualTo(URI.create("https://docmosis.example/rs/convert"));
          assertThat(properties.getDocmosis().getRenderEndpoint())
              .isEqualTo(URI.create("https://docmosis.example/rs/render"));
          assertThat(properties.getDocmosis().getAccessKey()).isEqualTo("test-access-key");
          assertThat(properties.getDocmosis().getConnectTimeout())
              .isEqualTo(Duration.ofSeconds(15));
          assertThat(properties.getDocmosis().getReadTimeout()).isEqualTo(Duration.ofMinutes(2));
          assertThat(properties.getDocmosis().getMaxSourceBytes()).isEqualTo(1048576L);
          assertThat(properties.getDocmosis().getRetryAttempts()).isEqualTo(3);
          assertThat(properties.getCdam().getJurisdictionId()).isEqualTo("DIVORCE");
          assertThat(properties.getCdam().getCaseTypeId()).isEqualTo("NFD");
          assertThat(properties.getCdam().getClassification())
              .isEqualTo(Classification.RESTRICTED);
        });
  }

  @Test
  void environmentVariableStyleKeysBindAndSatisfyTheConditions() {
    // The exact shape a service gets from the design doc's yaml: properties whose values come
    // from DOCMOSIS_*/TORNADO_* environment variables. Spring's relaxed binding also accepts the
    // env-var form of the key itself, so this exercises the strictest mapping.
    runner.withUserConfiguration(RendererDependenciesConfig.class)
        .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
            new SystemEnvironmentPropertySource("test-systemEnvironment", Map.of(
                "CCD_BUNDLING_DOCMOSIS_CONVERT_ENDPOINT", "https://docmosis.example/rs/convert",
                "CCD_BUNDLING_DOCMOSIS_RENDER_ENDPOINT", "https://docmosis.example/rs/render",
                "CCD_BUNDLING_DOCMOSIS_ACCESS_KEY", "env-access-key",
                "CCD_BUNDLING_MAX_CONCURRENT_RENDERS", "3"))))
        .run(context -> {
          assertThat(context).hasSingleBean(DocmosisRenderService.class);
          BundlingProperties properties = context.getBean(BundlingProperties.class);
          assertThat(properties.getDocmosis().getAccessKey()).isEqualTo("env-access-key");
          assertThat(properties.getMaxConcurrentRenders()).isEqualTo(3);
          assertThat(context.getBean(BundleRenderer.class).handledMediaTypes())
              .contains("application/msword");
        });
  }

  @Test
  void limitsPropertiesOverrideTheDefaultsPerField() {
    runner.withUserConfiguration(RendererDependenciesConfig.class)
        .withPropertyValues(
            "ccd.bundling.limits.max-document-count=5",
            "ccd.bundling.limits.max-total-pages=50",
            "ccd.bundling.limits.max-elapsed=90s")
        .run(context -> {
          BundleLimits limits = context.getBean(BundleRenderer.class).limits();
          BundleLimits defaults = BundleLimits.defaults();
          assertThat(limits.maxDocumentCount()).isEqualTo(5);
          assertThat(limits.maxTotalPages()).isEqualTo(50);
          assertThat(limits.maxElapsed()).isEqualTo(Duration.ofSeconds(90));
          assertThat(limits.maxSourceBytesPerDocument())
              .isEqualTo(defaults.maxSourceBytesPerDocument());
          assertThat(limits.maxOfficeSourceBytesPerDocument())
              .isEqualTo(defaults.maxOfficeSourceBytesPerDocument());
          assertThat(limits.maxOutputBytes()).isEqualTo(defaults.maxOutputBytes());
        });
  }

  @Test
  void unsetLimitsKeepEveryDefault() {
    runner.withUserConfiguration(RendererDependenciesConfig.class)
        .run(context -> assertThat(context.getBean(BundleRenderer.class).limits())
            .isEqualTo(BundleLimits.defaults()));
  }

  @Test
  void theAccessKeyNeverAppearsInToStrings() {
    runner.withUserConfiguration(RendererDependenciesConfig.class)
        .withPropertyValues(
            "ccd.bundling.docmosis.convert-endpoint=https://docmosis.example/rs/convert",
            "ccd.bundling.docmosis.render-endpoint=https://docmosis.example/rs/render",
            "ccd.bundling.docmosis.access-key=super-secret-key")
        .run(context -> {
          BundlingProperties properties = context.getBean(BundlingProperties.class);
          assertThat(properties.toString()).doesNotContain("super-secret-key");
          assertThat(properties.getDocmosis().toString()).doesNotContain("super-secret-key");
          assertThat(context.getBean(DocmosisRenderService.class).toString())
              .doesNotContain("super-secret-key");
        });
  }
}
