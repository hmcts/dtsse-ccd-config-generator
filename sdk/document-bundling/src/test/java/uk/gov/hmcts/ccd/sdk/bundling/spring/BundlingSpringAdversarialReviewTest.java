package uk.gov.hmcts.ccd.sdk.bundling.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDestination;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtension;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtensionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.cdam.BundlingAuthenticationProvider;
import uk.gov.hmcts.ccd.sdk.bundling.cdam.CdamBundleDestination;
import uk.gov.hmcts.ccd.sdk.bundling.cdam.CdamDocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobWorker;
import uk.gov.hmcts.ccd.sdk.bundling.testing.FilesystemBundleDestination;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.ccd.document.am.model.Classification;

/**
 * Adversarial review counter-examples for {@link BundlingAutoConfiguration}: condition-evaluation
 * ordering against beans contributed by other auto-configurations, secret leakage through the
 * wiring log, empty-string property edges, provider-name collisions, and failure-signal quality.
 *
 * <p>All findings are fixed: the formerly {@code @Disabled} desired-behaviour tests are enabled,
 * and the tests that pinned each defect's failure mode now assert the corrected behaviour.
 */
@ExtendWith(OutputCaptureExtension.class)
class BundlingSpringAdversarialReviewTest {

  private static final String[] CDAM_PROPERTIES = {
      "ccd.bundling.cdam.jurisdiction-id=DIVORCE",
      "ccd.bundling.cdam.case-type-id=NFD",
      "ccd.bundling.cdam.classification=RESTRICTED",
  };

  private static final String[] DOCMOSIS_PROPERTIES = {
      "ccd.bundling.docmosis.convert-endpoint=https://docmosis.example/rs/convert",
      "ccd.bundling.docmosis.render-endpoint=https://docmosis.example/rs/render",
      "ccd.bundling.docmosis.access-key=adversarial-super-secret-key",
  };

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(BundlingAutoConfiguration.class));

  @Configuration
  static class CdamBeansConfig {
    @Bean
    CaseDocumentClientApi caseDocumentClientApi() {
      return mock(CaseDocumentClientApi.class);
    }

    @Bean
    BundlingAuthenticationProvider bundlingAuthenticationProvider() {
      return new BundlingAuthenticationProvider() {
        @Override
        public String systemUserToken() {
          return "Bearer system-user";
        }

        @Override
        public String serviceToken() {
          return "service";
        }
      };
    }
  }

  @Configuration
  static class ResolverConfig {
    @Bean
    DocumentResolver caseDocumentsResolver() {
      return new SpringWiringFixtures.FixturePdfResolver("case-documents");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 1. Condition-evaluation ordering against beans from OTHER auto-configurations.
  //
  // The real CaseDocumentClientApi comes from ccd-case-document-am-client's
  // CaseDocumentManagementClientAutoConfiguration (spring.factories, @EnableFeignClients,
  // package uk.gov.hmcts.reform.ccd.document.am.config), which sorts alphabetically AFTER
  // "uk.gov.hmcts.ccd.sdk.bundling.spring". FINDING-1 fix: the CDAM adapters resolve their
  // collaborators via ObjectProvider at instantiation time (plus an afterName ordering on the
  // real client auto-configuration), so registration order no longer decides the wiring in
  // either direction.
  // ---------------------------------------------------------------------------------------------

  @Test
  void cdamBeansFromAnAlphabeticallyEarlierAutoConfigurationWire() {
    // Control: same beans, same properties, only the auto-configuration ordering differs.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            BundlingAutoConfiguration.class, AaaCdamConsumerBeansAutoConfiguration.class))
        .withPropertyValues(CDAM_PROPERTIES)
        .run(context -> {
          assertThat(context).hasSingleBean(CdamBundleDestination.class);
          assertThat(context).hasSingleBean(CdamDocumentResolver.class);
          assertThat(context).hasSingleBean(BundleRenderer.class);
        });
  }

  @Test
  //  FINDING-1 (fixed): with identical, correct configuration the CDAM adapters wire regardless
  //  of which auto-configuration contributes the client beans.
  void cdamBeansFromAnAlphabeticallyLaterAutoConfigurationShouldStillWire() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            BundlingAutoConfiguration.class, ZzzCdamConsumerBeansAutoConfiguration.class))
        .withPropertyValues(CDAM_PROPERTIES)
        .run(context -> {
          assertThat(context).hasSingleBean(CdamBundleDestination.class);
          assertThat(context).hasSingleBean(CdamDocumentResolver.class);
        });
  }

  @Test
  void cdamWiringFromALaterAutoConfigurationIsCompleteAndWarnFree(CapturedOutput output) {
    // FINDING-1 (fixed): correct configuration wires fully whatever the registration order, and
    // a fully wired context emits no partial-configuration WARN.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            BundlingAutoConfiguration.class, ZzzCdamConsumerBeansAutoConfiguration.class))
        .withPropertyValues(CDAM_PROPERTIES)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(CaseDocumentClientApi.class);
          assertThat(context).hasSingleBean(BundlingAuthenticationProvider.class);
          assertThat(context).hasSingleBean(CdamBundleDestination.class);
          assertThat(context).hasSingleBean(CdamDocumentResolver.class);
          assertThat(context).hasSingleBean(BundleRenderer.class);
        });
    assertThat(output.getOut()).doesNotContain("WARN");
  }

  @Test
  void aConsumerDestinationFromALaterAutoConfigurationWinsCleanly() {
    // FINDING-1 (fixed, inverse direction): a consumer destination contributed by a later
    // unordered auto-configuration wins; the CDAM destination backs off at instantiation time
    // instead of duplicating it into a no-unique-bean failure. The CDAM resolver still wires —
    // it yields only to a consumer-defined CdamDocumentResolver.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            BundlingAutoConfiguration.class, ZzzConsumerDestinationAutoConfiguration.class))
        .withUserConfiguration(CdamBeansConfig.class)
        .withPropertyValues(CDAM_PROPERTIES)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(CdamBundleDestination.class);
          assertThat(context.getBean(BundleDestination.class))
              .isInstanceOf(FilesystemBundleDestination.class);
          assertThat(context).hasSingleBean(CdamDocumentResolver.class);
          assertThat(context).hasSingleBean(BundleRenderer.class);
        });
  }

  // ---------------------------------------------------------------------------------------------
  // 2. Secret exposure through the wiring INFO log and startup failures.
  // ---------------------------------------------------------------------------------------------

  @Test
  void theWiringInfoLogNeverContainsTheAccessKey(CapturedOutput output) {
    runner.withUserConfiguration(CdamBeansConfig.class)
        .withPropertyValues(CDAM_PROPERTIES)
        .withPropertyValues(DOCMOSIS_PROPERTIES)
        .run(context -> assertThat(context).hasSingleBean(BundleRenderer.class));
    assertThat(output.getAll()).contains("Auto-configured BundleRenderer");
    assertThat(output.getAll()).doesNotContain("adversarial-super-secret-key");
  }

  @Test
  void aBlankAccessKeyFailsStartupWithoutLeakingAndWithoutSilentBackOff(CapturedOutput output) {
    // An empty value (the classic `access-key: ${DOCMOSIS_ACCESS_KEY:}` yaml default) still
    // MATCHES @ConditionalOnProperty — present-and-not-"false" — so the bean is attempted and
    // DocmosisConnection's validation fails the context. Fail-fast is right; this pins that the
    // failure is key-free. FINDING-4 (fixed): the wrapped failure now also names the
    // ccd.bundling.docmosis.access-key property and the empty env-var default as the usual
    // cause, with the original "accessKey must be provided" kept as the root cause.
    runner.withUserConfiguration(CdamBeansConfig.class)
        .withPropertyValues(CDAM_PROPERTIES)
        .withPropertyValues(
            "ccd.bundling.docmosis.convert-endpoint=https://docmosis.example/rs/convert",
            "ccd.bundling.docmosis.render-endpoint=https://docmosis.example/rs/render",
            "ccd.bundling.docmosis.access-key=")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("accessKey");
          assertThat(String.valueOf(context.getStartupFailure()))
              .contains("ccd.bundling.docmosis.access-key");
        });
    assertThat(output.getAll()).doesNotContain("adversarial-super-secret-key");
  }

  // ---------------------------------------------------------------------------------------------
  // 3. Property-binding edges.
  // ---------------------------------------------------------------------------------------------

  @Test
  void anEmptyClassificationFailsStartupInsteadOfDefaulting() {
    // `classification:` empty also matches @ConditionalOnProperty; the binder converts "" to a
    // null enum; CdamUploadSettings must then refuse to default it. Legal-document safety pin.
    runner.withUserConfiguration(CdamBeansConfig.class)
        .withPropertyValues(
            "ccd.bundling.cdam.jurisdiction-id=DIVORCE",
            "ccd.bundling.cdam.case-type-id=NFD",
            "ccd.bundling.cdam.classification=")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(String.valueOf(context.getStartupFailure()))
              .contains("classification");
        });
  }

  @Test
  void lowercaseClassificationBindsCaseInsensitively() {
    runner.withUserConfiguration(CdamBeansConfig.class)
        .withPropertyValues(
            "ccd.bundling.cdam.jurisdiction-id=DIVORCE",
            "ccd.bundling.cdam.case-type-id=NFD",
            "ccd.bundling.cdam.classification=restricted")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(BundlingProperties.class).getCdam().getClassification())
              .isEqualTo(Classification.RESTRICTED);
          assertThat(context).hasSingleBean(CdamBundleDestination.class);
        });
  }

  @Test
  void isoDurationFormatBindsAlongsideTheSimpleForm() {
    runner.withUserConfiguration(ResolverConfig.class)
        .withBean("dest", BundleDestination.class,
            () -> new FilesystemBundleDestination(Path.of(
                System.getProperty("java.io.tmpdir"), "adversarial-unused")))
        .withPropertyValues("ccd.bundling.limits.max-elapsed=PT2M")
        .run(context -> assertThat(
            context.getBean(BundleRenderer.class).limits().maxElapsed())
            .isEqualTo(Duration.ofMinutes(2)));
  }

  @Test
  void anUnusableTempDirectoryFailsStartupNamingTheProperty(@TempDir Path dir) throws Exception {
    // FINDING-3 (fixed): temp-directory pointing at a regular FILE (or any unusable path) is
    // probed during auto-configuration and fails context refresh with a message naming the
    // property, instead of surfacing as a per-bundle runtime failure at first render.
    Path notADirectory = Files.createFile(dir.resolve("not-a-directory"));
    runner.withUserConfiguration(ResolverConfig.class)
        .withBean("dest", BundleDestination.class, () -> new FilesystemBundleDestination(dir))
        .withPropertyValues("ccd.bundling.temp-directory=" + notADirectory)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(String.valueOf(context.getStartupFailure()))
              .contains("ccd.bundling.temp-directory");
        });
  }

  // ---------------------------------------------------------------------------------------------
  // 4. Failure-signal quality.
  // ---------------------------------------------------------------------------------------------

  @Test
  //  FINDING-2 (fixed): a consumer who set two of the three ccd.bundling.cdam.* properties has
  //  unambiguously tried to configure CDAM; the module says so at WARN instead of silently
  //  backing off with only the DEBUG condition report.
  void partialCdamConfigurationShouldWarn(CapturedOutput output) {
    runner.withUserConfiguration(CdamBeansConfig.class)
        .withPropertyValues(
            "ccd.bundling.cdam.jurisdiction-id=DIVORCE",
            "ccd.bundling.cdam.case-type-id=NFD")
        .run(context -> assertThat(context).hasNotFailed());
    assertThat(output.getAll()).containsIgnoringCase("cdam");
    assertThat(output.getAll()).contains("WARN");
  }

  @Test
  void partialCdamConfigurationWarnNamesTheMissingKeyExactly(CapturedOutput output) {
    // FINDING-2 (fixed): the WARN names the exact missing property key, the beans still back
    // off, and the context starts cleanly. A service that sets NO cdam property stays silent —
    // covered by cdamWiringFromALaterAutoConfigurationIsCompleteAndWarnFree's no-WARN check and
    // the enabled suite's unconfigured runs.
    runner.withUserConfiguration(CdamBeansConfig.class)
        .withPropertyValues(
            "ccd.bundling.cdam.jurisdiction-id=DIVORCE",
            "ccd.bundling.cdam.case-type-id=NFD")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(CdamBundleDestination.class);
          assertThat(context).doesNotHaveBean(BundleRenderer.class);
        });
    assertThat(output.getAll()).contains("WARN");
    assertThat(output.getAll()).contains("ccd.bundling.cdam.classification");
  }

  @Test
  void aConsumerResolverReusingTheCdamProviderNameFailsStartupNamingTheProvider() {
    // The CDAM resolver backs off only to a consumer-defined CdamDocumentResolver (a final
    // class). A consumer resolver that merely reuses the provider name "cdam" registers
    // alongside it and the builder rejects the collision at refresh. FINDING-4 (fixed): the
    // failure keeps the collision text and now also names the remedies — rename the provider,
    // or define your own CdamDocumentResolver bean.
    runner.withUserConfiguration(CdamBeansConfig.class)
        .withBean("consumerCdamResolver", DocumentResolver.class,
            () -> new SpringWiringFixtures.FixturePdfResolver("cdam"))
        .withPropertyValues(CDAM_PROPERTIES)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(String.valueOf(context.getStartupFailure()))
              .contains("already registered for provider 'cdam'")
              .contains("rename")
              .contains("CdamDocumentResolver");
        });
  }

  // ---------------------------------------------------------------------------------------------
  // 5. Composition with the job runner when the CONSUMER supplies the renderer.
  // ---------------------------------------------------------------------------------------------

  @Test
  void theJobWorkerWiresAgainstAConsumerDefinedRenderer() {
    BundleRenderer custom = new BundleRenderer() {
      @Override
      public BundleResult render(BundleRequest request, BundleExecutionContext context) {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.util.Set<String> handledMediaTypes() {
        return java.util.Set.of("application/pdf");
      }
    };
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            BundleJobAutoConfiguration.class, BundlingAutoConfiguration.class))
        .withBean("jdbc", NamedParameterJdbcTemplate.class,
            () -> new NamedParameterJdbcTemplate(
                new DriverManagerDataSource("jdbc:postgresql://localhost/unused")))
        .withBean("customRenderer", BundleRenderer.class, () -> custom)
        .run(context -> {
          assertThat(context).hasSingleBean(BundleJobWorker.class);
          assertThat(context.getBean(BundleRenderer.class)).isSameAs(custom);
        });
  }

  // ---------------------------------------------------------------------------------------------
  // 6. Extension registration: duplicate names still apply, but are called out at WARN
  // (FINDING-5 fixed) because registry error attribution is ambiguous otherwise.
  // ---------------------------------------------------------------------------------------------

  @Test
  void twoExtensionsWithTheSameNameBothApplyAndAreWarnedAbout(CapturedOutput output) {
    BundlingExtension first = extension("dup",
        registry -> registry.addHandler("application/x-one", (source, context) -> {
          throw new UnsupportedOperationException();
        }));
    BundlingExtension second = extension("dup",
        registry -> registry.addHandler("application/x-two", (source, context) -> {
          throw new UnsupportedOperationException();
        }));
    runner.withUserConfiguration(ResolverConfig.class)
        .withBean("dest", BundleDestination.class,
            () -> new FilesystemBundleDestination(Path.of(
                System.getProperty("java.io.tmpdir"), "adversarial-unused")))
        .withBean("firstDup", BundlingExtension.class, () -> first)
        .withBean("secondDup", BundlingExtension.class, () -> second)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(BundleRenderer.class).handledMediaTypes())
              .contains("application/x-one", "application/x-two");
        });
    assertThat(output.getAll()).contains("WARN");
    assertThat(output.getAll()).contains("dup");
  }

  private static BundlingExtension extension(
      String name, java.util.function.Consumer<BundlingExtensionContext> configure) {
    return new BundlingExtension() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public void configure(BundlingExtensionContext context) {
        configure.accept(context);
      }
    };
  }
}

/**
 * Stands in for a consumer/library auto-configuration whose class name sorts BEFORE
 * {@code BundlingAutoConfiguration} and that carries no ordering annotations.
 */
@AutoConfiguration
class AaaCdamConsumerBeansAutoConfiguration {
  @Bean
  CaseDocumentClientApi caseDocumentClientApi() {
    return mock(CaseDocumentClientApi.class);
  }

  @Bean
  BundlingAuthenticationProvider bundlingAuthenticationProvider() {
    return new BundlingAuthenticationProvider() {
      @Override
      public String systemUserToken() {
        return "Bearer system-user";
      }

      @Override
      public String serviceToken() {
        return "service";
      }
    };
  }
}

/**
 * Stands in for the REAL client auto-configuration
 * ({@code uk.gov.hmcts.reform.ccd.document.am.config.CaseDocumentManagementClientAutoConfiguration},
 * which sorts after {@code uk.gov.hmcts.ccd.sdk.bundling.spring.BundlingAutoConfiguration}
 * alphabetically and declares no ordering relative to it).
 */
@AutoConfiguration
class ZzzCdamConsumerBeansAutoConfiguration {
  @Bean
  CaseDocumentClientApi caseDocumentClientApi() {
    return mock(CaseDocumentClientApi.class);
  }

  @Bean
  BundlingAuthenticationProvider bundlingAuthenticationProvider() {
    return new BundlingAuthenticationProvider() {
      @Override
      public String systemUserToken() {
        return "Bearer system-user";
      }

      @Override
      public String serviceToken() {
        return "service";
      }
    };
  }
}

/** A consumer destination contributed by a later, unordered auto-configuration. */
@AutoConfiguration
class ZzzConsumerDestinationAutoConfiguration {
  @Bean
  BundleDestination consumerDestination() {
    return new FilesystemBundleDestination(
        Path.of(System.getProperty("java.io.tmpdir"), "adversarial-late-destination"));
  }
}
