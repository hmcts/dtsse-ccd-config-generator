package uk.gov.hmcts.ccd.sdk.bundling.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtension;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtensionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.HttpDocmosisRenderService;

class BundlingAutoConfigurationTest {
  private static final String CONVERT_ENDPOINT =
      "ccd.bundling.docmosis.convert-endpoint=https://docmosis.example/rs/convert";
  private static final String ACCESS_KEY = "ccd.bundling.docmosis.access-key=test-access-key";
  private static final String RENDER_ENDPOINT =
      "ccd.bundling.docmosis.render-endpoint=https://docmosis.example/rs/render";

  private final FixturePdfResolver caseDocuments = new FixturePdfResolver("case-documents");
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(BundlingAutoConfiguration.class))
      .withBean("caseDocumentsResolver", DocumentResolver.class, () -> caseDocuments);

  @Test
  void disabledRegistersNothing() {
    runner.withPropertyValues("ccd.bundling.enabled=false", CONVERT_ENDPOINT, ACCESS_KEY).run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(BundlingProperties.class);
      assertThat(context).doesNotHaveBean(BundleRenderer.class);
      assertThat(context).doesNotHaveBean(DocmosisRenderService.class);
    });
  }

  @Test
  void officeHandlersRegisterOnlyWhenBothDocmosisPropertiesAreSet() {
    runner.run(context -> {
      assertThat(context).doesNotHaveBean(DocmosisRenderService.class);
      assertThat(context.getBean(BundleRenderer.class).handledMediaTypes())
          .contains("application/pdf", "image/png", "audio/mpeg").doesNotContain("application/msword", "text/plain");
    });
    runner.withPropertyValues(CONVERT_ENDPOINT).run(context -> // half the pair is not enough
        assertThat(context.getBean(BundleRenderer.class).handledMediaTypes()).doesNotContain("application/msword"));
    runner.withPropertyValues(CONVERT_ENDPOINT, ACCESS_KEY).run(context -> {
      DocmosisRenderService docmosis = context.getBean(DocmosisRenderService.class);
      assertThat(docmosis).isInstanceOf(HttpDocmosisRenderService.class);
      assertThat(docmosis.convertsFiles()).isTrue();
      assertThat(docmosis.rendersTemplates()).as("no render endpoint: no cover pages").isFalse();
      assertThat(context.getBean(BundleRenderer.class).handledMediaTypes())
          .contains("application/msword", "text/plain", "application/pdf");
    });
    runner.withPropertyValues(RENDER_ENDPOINT, ACCESS_KEY).run(context -> { // cover pages without conversion
      DocmosisRenderService docmosis = context.getBean(DocmosisRenderService.class);
      assertThat(docmosis.rendersTemplates()).isTrue();
      assertThat(docmosis.convertsFiles()).isFalse();
      assertThat(context.getBean(BundleRenderer.class).handledMediaTypes()).doesNotContain("application/msword");
    });
  }

  @Test
  void noResolverBeansMeansNoRenderer() {
    new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(BundlingAutoConfiguration.class))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(BundlingProperties.class);
          assertThat(context).doesNotHaveBean(BundleRenderer.class);
        });
  }

  @Test
  void consumerDefinedRendererWins() {
    BundleRenderer custom = new BundleRenderer() {
      @Override
      public BundleResult render(BundleRequest request, BundleExecutionContext context) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Set<String> handledMediaTypes() {
        return Set.of("application/x-custom");
      }
    };
    runner.withBean("customRenderer", BundleRenderer.class, () -> custom).run(context -> {
      assertThat(context).hasSingleBean(BundleRenderer.class);
      assertThat(context.getBean(BundleRenderer.class)).isSameAs(custom);
    });
  }

  @Test
  void everyResolverBeanIsReachableThroughARealRender(@TempDir Path temp) {
    FixturePdfResolver archive = new FixturePdfResolver("archive");
    runner.withBean("archiveResolver", DocumentResolver.class, () -> archive)
        .withPropertyValues("ccd.bundling.temp-directory=" + temp)
        .run(context -> {
          BundleRenderer renderer = context.getBean(BundleRenderer.class);
          try (BundleResult result = renderer.render(request(
              pdfDocument("d1", "case-documents"), pdfDocument("d2", "archive")), BundleExecutionContext.empty())) {
            assertThat(result.outcome()).isEqualTo(BundleOutcome.COMPLETED);
            assertThat(result.pageCount()).isEqualTo(4);
            assertThat(caseDocuments.batches).containsExactly(List.of(new DocumentReference("case-documents", "d1")));
            assertThat(archive.batches).containsExactly(List.of(new DocumentReference("archive", "d2")));
            try (Stream<Path> entries = Files.list(temp)) {
              assertThat(entries).as("the job directory is under the configured temp directory").hasSize(1);
            }
          }
          try (Stream<Path> entries = Files.list(temp)) {
            assertThat(entries).isEmpty();
          }
        });
  }

  @Test
  void extensionBeansApplyInOrder() {
    // The handlers are never invoked: only their registration order matters here.
    Consumer<BundlingExtensionContext> adds = registry -> registry.addHandler("application/x-custom", (s, c) -> null);
    Consumer<BundlingExtensionContext> replaces =
        registry -> registry.replaceHandler("application/x-custom", (s, c) -> null);
    runner.withBean("adds", BundlingExtension.class, () -> new OrderedExtension("adds-custom", 1, adds))
        .withBean("replaces", BundlingExtension.class, () -> new OrderedExtension("replaces-custom", 2, replaces))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(BundleRenderer.class).handledMediaTypes()).contains("application/x-custom");
        });
    // Reversing the orders makes replaceHandler run before addHandler, which the registry rejects:
    // extensions apply in @Order order rather than by registration accident.
    runner.withBean("adds", BundlingExtension.class, () -> new OrderedExtension("adds-custom", 2, adds))
        .withBean("replaces", BundlingExtension.class, () -> new OrderedExtension("replaces-custom", 1, replaces))
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("replaces-custom");
        });
  }

  @Test
  void propertiesBindIncludingLimitsAndEnvStyleKeys() {
    runner.withPropertyValues(
            "ccd.bundling.docmosis.connect-timeout=15s", "ccd.bundling.docmosis.read-timeout=2m",
            "ccd.bundling.docmosis.max-source-bytes=1048576", "ccd.bundling.limits.max-total-pages=50")
        .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
            new SystemEnvironmentPropertySource("test-systemEnvironment", Map.of(
                "CCD_BUNDLING_DOCMOSIS_CONVERT_ENDPOINT", "https://docmosis.example/rs/convert",
                "CCD_BUNDLING_DOCMOSIS_ACCESS_KEY", "super-secret-key",
                "CCD_BUNDLING_MAX_CONCURRENT_RENDERS", "3",
                "CCD_BUNDLING_LIMITS_MAXDOCUMENTCOUNT", "1"))))
        .run(context -> {
          BundlingProperties properties = context.getBean(BundlingProperties.class);
          assertThat(properties.getMaxConcurrentRenders()).isEqualTo(3);
          assertThat(properties.getDocmosis().getConnectTimeout()).isEqualTo(Duration.ofSeconds(15));
          assertThat(properties.getDocmosis().getReadTimeout()).isEqualTo(Duration.ofMinutes(2));
          assertThat(properties.getDocmosis().getMaxSourceBytes()).isEqualTo(1048576L);
          assertThat(properties.getLimits().getMaxTotalPages()).isEqualTo(50);
          assertThat(properties.getLimits().getMaxDocumentCount()).isEqualTo(1);
          assertThat(properties.getDocmosis().getAccessKey()).isEqualTo("super-secret-key");
          assertThat(properties.toString()).doesNotContain("super-secret-key");
          assertThat(context.getBean(DocmosisRenderService.class).toString()).doesNotContain("super-secret-key");

          // The env-bound document limit is what the auto-configured renderer enforces.
          BundleRenderer renderer = context.getBean(BundleRenderer.class);
          assertThat(renderer.handledMediaTypes()).contains("application/msword");
          BundleGenerationException failure = catchThrowableOfType(BundleGenerationException.class,
              () -> renderer.render(request(pdfDocument("d1", "case-documents"), pdfDocument("d2", "case-documents")),
                  BundleExecutionContext.empty()));
          assertThat(failure.code()).isEqualTo(BundleErrorCode.LIMIT_EXCEEDED);
          assertThat(failure.stage()).isEqualTo(BundleStage.VALIDATE);
        });
  }

  private static BundleDocument pdfDocument(String id, String provider) {
    return BundleDocument.builder().id(id).title("Document " + id)
        .reference(new DocumentReference(provider, id)).build();
  }

  private static BundleRequest request(BundleDocument... documents) {
    BundleSection.Builder root = BundleSection.builder("Case file");
    for (BundleDocument document : documents) {
      root.document(document);
    }
    return BundleRequest.builder().externalId(UUID.randomUUID()).title("Auto-configured bundle")
        .fileName("auto-configured-bundle.pdf").root(root.build()).build();
  }

  // An extension bean whose position in the extension order is its {@link Ordered} value.
  private record OrderedExtension(String name, int order, Consumer<BundlingExtensionContext> body)
      implements BundlingExtension, Ordered {
    @Override
    public void configure(BundlingExtensionContext context) {
      body.accept(context);
    }

    @Override
    public int getOrder() {
      return order;
    }
  }

  // Serves the one-page PDF fixture for every reference, recording each batch.
  private record FixturePdfResolver(String provider, List<List<DocumentReference>> batches)
      implements DocumentResolver {
    FixturePdfResolver(String provider) {
      this(provider, new CopyOnWriteArrayList<>());
    }

    @Override
    public ResolvedDocuments resolveAll(List<DocumentReference> references, BundleExecutionContext context) {
      batches.add(List.copyOf(references));
      Map<DocumentReference, ResolvedDocument> resolved = new LinkedHashMap<>();
      references.forEach(reference -> resolved.put(reference, new Pdf(reference.id() + ".pdf")));
      return ResolvedDocuments.allResolved(resolved);
    }
  }

  private record Pdf(String fileName) implements ResolvedDocument {
    private static final byte[] BYTES = onePagePdf();

    private static byte[] onePagePdf() {
      try (InputStream in = Pdf.class.getResourceAsStream("/fixtures/em-stitching/one-page.pdf")) {
        return in.readAllBytes();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public InputStream content() {
      return new ByteArrayInputStream(BYTES);
    }

    @Override
    public String mediaType() {
      return "application/pdf";
    }

    @Override
    public OptionalLong contentLength() {
      return OptionalLong.of(BYTES.length);
    }

    @Override
    public Optional<String> checksum() {
      return Optional.empty();
    }

    @Override
    public void close() {
    }
  }
}
