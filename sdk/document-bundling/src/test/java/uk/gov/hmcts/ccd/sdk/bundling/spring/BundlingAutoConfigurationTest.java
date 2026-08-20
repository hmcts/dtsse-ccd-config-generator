package uk.gov.hmcts.ccd.sdk.bundling.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDestination;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtension;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtensionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandler;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.cdam.BundlingAuthenticationProvider;
import uk.gov.hmcts.ccd.sdk.bundling.cdam.CdamBundleDestination;
import uk.gov.hmcts.ccd.sdk.bundling.cdam.CdamDocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.HttpDocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.testing.FilesystemBundleDestination;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

/**
 * The auto-configuration's conditions matrix: disabled registers nothing, Docmosis and CDAM wire
 * up exactly when their properties and beans are present, every bean yields to a consumer-defined
 * one, all resolver beans reach the renderer, and extensions apply in {@code @Order} order.
 */
class BundlingAutoConfigurationTest {

  private static final String[] DOCMOSIS_PROPERTIES = {
      "ccd.bundling.docmosis.convert-endpoint=https://docmosis.example/rs/convert",
      "ccd.bundling.docmosis.render-endpoint=https://docmosis.example/rs/render",
      "ccd.bundling.docmosis.access-key=test-access-key",
  };

  private static final String[] CDAM_PROPERTIES = {
      "ccd.bundling.cdam.jurisdiction-id=DIVORCE",
      "ccd.bundling.cdam.case-type-id=NFD",
      "ccd.bundling.cdam.classification=RESTRICTED",
  };

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(BundlingAutoConfiguration.class));

  @Configuration
  static class ResolverConfig {
    @Bean
    DocumentResolver caseDocumentsResolver() {
      return new SpringWiringFixtures.FixturePdfResolver("case-documents");
    }
  }

  @Configuration
  static class SecondResolverConfig {
    @Bean
    DocumentResolver archiveResolver() {
      return new SpringWiringFixtures.FixturePdfResolver("archive");
    }
  }

  @Configuration
  static class DestinationConfig {
    @Bean
    BundleDestination filesystemDestination() {
      return new FilesystemBundleDestination(
          Path.of(System.getProperty("java.io.tmpdir"), "bundling-autoconfiguration-test"));
    }
  }

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

  @Test
  void disabledRegistersNothing() {
    runner.withUserConfiguration(ResolverConfig.class, DestinationConfig.class, CdamBeansConfig.class)
        .withPropertyValues("ccd.bundling.enabled=false")
        .withPropertyValues(DOCMOSIS_PROPERTIES)
        .withPropertyValues(CDAM_PROPERTIES)
        .run(context -> {
          assertThat(context).doesNotHaveBean(BundlingProperties.class);
          assertThat(context).doesNotHaveBean(BundleRenderer.class);
          assertThat(context).doesNotHaveBean(DocmosisRenderService.class);
          assertThat(context).doesNotHaveBean(CdamBundleDestination.class);
          assertThat(context).doesNotHaveBean(CdamDocumentResolver.class);
        });
  }

  @Test
  void withoutDocmosisPropertiesOfficeTypesStayUnhandled() {
    runner.withUserConfiguration(ResolverConfig.class, DestinationConfig.class)
        .run(context -> {
          assertThat(context).doesNotHaveBean(DocmosisRenderService.class);
          assertThat(context).hasSingleBean(BundleRenderer.class);
          BundleRenderer renderer = context.getBean(BundleRenderer.class);
          assertThat(renderer.handledMediaTypes()).contains("application/pdf");
          assertThat(renderer.handledMediaTypes()).doesNotContain("application/msword");
        });
  }

  @Test
  void partialDocmosisPropertiesRegisterNoRenderService() {
    runner.withUserConfiguration(ResolverConfig.class, DestinationConfig.class)
        .withPropertyValues(
            "ccd.bundling.docmosis.convert-endpoint=https://docmosis.example/rs/convert")
        .run(context -> {
          assertThat(context).doesNotHaveBean(DocmosisRenderService.class);
          assertThat(context.getBean(BundleRenderer.class).handledMediaTypes())
              .doesNotContain("application/msword");
        });
  }

  @Test
  void docmosisPropertiesRegisterTheOfficeHandlers() {
    runner.withUserConfiguration(ResolverConfig.class, DestinationConfig.class)
        .withPropertyValues(DOCMOSIS_PROPERTIES)
        .run(context -> {
          assertThat(context).hasSingleBean(DocmosisRenderService.class);
          assertThat(context.getBean(DocmosisRenderService.class))
              .isInstanceOf(HttpDocmosisRenderService.class);
          assertThat(context.getBean(BundleRenderer.class).handledMediaTypes())
              .contains("application/msword", "text/plain");
        });
  }

  @Test
  void cdamBeansAndPropertiesWireTheCdamAdaptersIntoTheRenderer() {
    runner.withUserConfiguration(CdamBeansConfig.class)
        .withPropertyValues(CDAM_PROPERTIES)
        .run(context -> {
          assertThat(context).hasSingleBean(CdamBundleDestination.class);
          assertThat(context).hasSingleBean(CdamDocumentResolver.class);
          // The renderer built successfully: the CDAM resolver is its only resolver (the
          // builder rejects an empty resolver set) and the CDAM destination its destination
          // (the auto-configuration fails descriptively without one).
          assertThat(context).hasSingleBean(BundleRenderer.class);
          assertThat(context.getBean(BundleDestination.class))
              .isSameAs(context.getBean(CdamBundleDestination.class));
        });
  }

  @Test
  void fullCdamPropertiesWithoutTheAuthenticationProviderBeanFailDescriptively() {
    // All three properties set is unambiguous intent to use CDAM, and with no other wiring the
    // renderer cannot be assembled — so this fails fast, naming the missing collaborator bean,
    // rather than silently starting a service whose bundling cannot work.
    runner.withPropertyValues(CDAM_PROPERTIES)
        .withBean(CaseDocumentClientApi.class, () -> mock(CaseDocumentClientApi.class))
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(String.valueOf(context.getStartupFailure()))
              .contains("BundlingAuthenticationProvider");
        });
  }

  @Test
  void cdamWithoutThePropertiesBacksOff() {
    runner.withUserConfiguration(CdamBeansConfig.class)
        .withPropertyValues("ccd.bundling.cdam.jurisdiction-id=DIVORCE")
        .run(context -> {
          assertThat(context).doesNotHaveBean(CdamBundleDestination.class);
          assertThat(context).doesNotHaveBean(CdamDocumentResolver.class);
        });
  }

  @Test
  void aConsumerDefinedRendererWins() {
    BundleRenderer custom = new BundleRenderer() {
      @Override
      public BundleResult render(BundleRequest request, BundleExecutionContext context) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Set<String> handledMediaTypes() {
        return Set.of("application/pdf");
      }
    };
    runner.withUserConfiguration(ResolverConfig.class, DestinationConfig.class)
        .withBean("customRenderer", BundleRenderer.class, () -> custom)
        .run(context -> {
          assertThat(context).hasSingleBean(BundleRenderer.class);
          assertThat(context.getBean(BundleRenderer.class)).isSameAs(custom);
        });
  }

  @Test
  void aConsumerDefinedDestinationWinsOverCdam() {
    runner.withUserConfiguration(CdamBeansConfig.class, DestinationConfig.class)
        .withPropertyValues(CDAM_PROPERTIES)
        .run(context -> {
          assertThat(context).doesNotHaveBean(CdamBundleDestination.class);
          assertThat(context.getBean(BundleDestination.class))
              .isInstanceOf(FilesystemBundleDestination.class);
          // The CDAM resolver still registers: it backs off only to a consumer CdamDocumentResolver.
          assertThat(context).hasSingleBean(CdamDocumentResolver.class);
          assertThat(context).hasSingleBean(BundleRenderer.class);
        });
  }

  @Test
  void aConsumerDefinedDocmosisRenderServiceWins() {
    DocmosisRenderService custom = mock(DocmosisRenderService.class);
    runner.withUserConfiguration(ResolverConfig.class, DestinationConfig.class)
        .withBean("customDocmosis", DocmosisRenderService.class, () -> custom)
        .withPropertyValues(DOCMOSIS_PROPERTIES)
        .run(context -> {
          assertThat(context).hasSingleBean(DocmosisRenderService.class);
          assertThat(context.getBean(DocmosisRenderService.class)).isSameAs(custom);
          assertThat(context.getBean(BundleRenderer.class).handledMediaTypes())
              .contains("application/msword");
        });
  }

  @Test
  void noResolverBeansMeansNoRenderer() {
    runner.withUserConfiguration(DestinationConfig.class).run(context -> {
      assertThat(context).doesNotHaveBean(BundleRenderer.class);
      assertThat(context).hasNotFailed();
    });
  }

  @Test
  void aResolverWithoutAnyDestinationFailsDescriptively() {
    runner.withUserConfiguration(ResolverConfig.class).run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).rootCause()
          .hasMessageContaining("BundleDestination")
          .hasMessageContaining("ccd.bundling.cdam.jurisdiction-id")
          .hasMessageContaining("ccd.bundling.cdam.classification");
    });
  }

  @Test
  void everyResolverBeanIsReachableThroughARender(@TempDir Path output) {
    SpringWiringFixtures.FixturePdfResolver caseDocuments =
        new SpringWiringFixtures.FixturePdfResolver("case-documents");
    SpringWiringFixtures.FixturePdfResolver archive =
        new SpringWiringFixtures.FixturePdfResolver("archive");
    runner
        .withBean("caseDocumentsResolver", DocumentResolver.class, () -> caseDocuments)
        .withBean("archiveResolver", DocumentResolver.class, () -> archive)
        .withBean("filesystemDestination", BundleDestination.class,
            () -> new FilesystemBundleDestination(output))
        .run(context -> {
          BundleRenderer renderer = context.getBean(BundleRenderer.class);
          BundleResult result = renderer.render(
              SpringWiringFixtures.request(
                  SpringWiringFixtures.pdfDocument("d1", "From case documents", "case-documents"),
                  SpringWiringFixtures.pdfDocument("d2", "From the archive", "archive")),
              BundleExecutionContext.empty());
          assertThat(result.outcome()).isEqualTo(BundleOutcome.COMPLETED);
          assertThat(caseDocuments.batches).hasSize(1);
          assertThat(archive.batches).hasSize(1);
        });
  }

  /** A handler stub for extension registrations that are never rendered in these tests. */
  private static DocumentHandler unusedHandler() {
    return (source, context) -> {
      throw new UnsupportedOperationException("never rendered in this test");
    };
  }

  private static BundlingExtension extension(String name, java.util.function.Consumer<BundlingExtensionContext> configure) {
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

  @Configuration
  static class OrderedExtensionsConfig {
    @Bean
    @Order(1)
    BundlingExtension addsCustomType() {
      return extension("adds-custom",
          registry -> registry.addHandler("application/x-custom", unusedHandler()));
    }

    @Bean
    @Order(2)
    BundlingExtension replacesCustomType() {
      return extension("replaces-custom",
          registry -> registry.replaceHandler("application/x-custom", unusedHandler()));
    }
  }

  @Configuration
  static class ReversedExtensionsConfig {
    @Bean
    @Order(2)
    BundlingExtension addsCustomType() {
      return extension("adds-custom",
          registry -> registry.addHandler("application/x-custom", unusedHandler()));
    }

    @Bean
    @Order(1)
    BundlingExtension replacesCustomType() {
      return extension("replaces-custom",
          registry -> registry.replaceHandler("application/x-custom", unusedHandler()));
    }
  }

  @Test
  void extensionBeansApplyInOrder() {
    runner.withUserConfiguration(
            ResolverConfig.class, DestinationConfig.class, OrderedExtensionsConfig.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(BundleRenderer.class).handledMediaTypes())
              .contains("application/x-custom");
        });
  }

  @Test
  void extensionOrderIsRespectedNotIncidental() {
    // Reversing the @Order values makes replaceHandler run before addHandler, which the
    // registry rejects — proving extensions apply in @Order order rather than by accident.
    runner.withUserConfiguration(
            ResolverConfig.class, DestinationConfig.class, ReversedExtensionsConfig.class)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("replaces-custom");
        });
  }

  @Test
  void secondConsumerResolverRegistersAlongsideTheFirst() {
    runner.withUserConfiguration(
            ResolverConfig.class, SecondResolverConfig.class, DestinationConfig.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBeansOfType(DocumentResolver.class)).hasSize(2);
          assertThat(context).hasSingleBean(BundleRenderer.class);
        });
  }
}
