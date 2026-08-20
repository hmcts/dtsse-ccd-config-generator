package uk.gov.hmcts.ccd.sdk.bundling.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;

class BundleRendererBuilderTest {

  private static DocumentResolver resolver(String provider) {
    return new DocumentResolver() {
      @Override
      public String provider() {
        return provider;
      }

      @Override
      public ResolvedDocuments resolveAll(
          List<DocumentReference> references, BundleExecutionContext context) {
        return ResolvedDocuments.allResolved(Map.of());
      }
    };
  }

  private static BundleDestination destination() {
    return (artifact, context) -> new StoredBundle(
        "http://dm-store/documents/1", "http://dm-store/documents/1/binary",
        artifact.fileName(), artifact.mediaType(), 1, "sha", Optional.empty());
  }

  private static DocmosisRenderService docmosis() {
    return new DocmosisRenderService() {
      @Override
      public Path convertToPdf(Path source, String fileName, String mediaType) {
        return source;
      }

      @Override
      public Path renderTemplate(String templateName, Map<String, Object> payload) {
        return Path.of("unused.pdf");
      }
    };
  }

  @Test
  void requiresAResolver() {
    assertThatThrownBy(() -> BundleRenderer.builder().destination(destination()).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DocumentResolver");
  }

  @Test
  void requiresADestination() {
    assertThatThrownBy(() -> BundleRenderer.builder().resolver(resolver("case-documents")).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("BundleDestination");
  }

  @Test
  void rejectsDuplicateResolverProviders() {
    assertThatThrownBy(() -> BundleRenderer.builder()
        .resolver(resolver("case-documents"))
        .resolver(resolver("case-documents")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("case-documents");
  }

  @Test
  void defaultRegistryHandlesPdfImagesAndMediaButNotOfficeWithoutDocmosis() {
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolver("case-documents"))
        .destination(destination())
        .build();

    assertThat(renderer.handledMediaTypes())
        .contains(BuiltInMediaTypes.PDF)
        .contains("image/png", "image/svg+xml")
        .contains("audio/mpeg", "video/mp4")
        .doesNotContainAnyElementsOf(BuiltInMediaTypes.OFFICE);
  }

  @Test
  void configuringDocmosisRegistersTheOfficeHandlers() {
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolver("case-documents"))
        .destination(destination())
        .docmosis(docmosis())
        .build();

    assertThat(renderer.handledMediaTypes()).containsAll(BuiltInMediaTypes.OFFICE);
  }

  @Test
  void supportsTheDesignDocumentExtensionExample() {
    DocumentHandler brandedMediaHandler = (source, context) -> HandledDocument.of(Path.of("x.pdf"));
    DocumentHandler msgHandler = (source, context) -> HandledDocument.of(Path.of("y.pdf"));

    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolver("case-documents"))
        .destination(destination())
        .extension(new BundlingExtension() {
          @Override
          public String name() {
            return "et-media-pages";
          }

          @Override
          public void configure(BundlingExtensionContext context) {
            context.replaceHandler("video/mp4", brandedMediaHandler);
            context.addHandler("application/vnd.ms-outlook", msgHandler);
          }
        })
        .build();

    assertThat(renderer.handledMediaTypes()).contains("application/vnd.ms-outlook");
  }

  @Test
  void extensionMistakesFailAtBuildTime() {
    BundleRendererBuilder builder = BundleRenderer.builder()
        .resolver(resolver("case-documents"))
        .destination(destination())
        .extension(new BundlingExtension() {
          @Override
          public String name() {
            return "clumsy";
          }

          @Override
          public void configure(BundlingExtensionContext context) {
            context.addHandler(BuiltInMediaTypes.PDF, (source, ctx) ->
                HandledDocument.of(Path.of("z.pdf")));
          }
        });

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("clumsy")
        .hasMessageContaining(BuiltInMediaTypes.PDF);
  }

  @Test
  void rejectsANonPositiveConcurrencyLimit() {
    assertThatThrownBy(() -> BundleRenderer.builder().maxConcurrentRenders(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxConcurrentRenders");
  }

  @Test
  void exposesTheConfiguredLimits() {
    BundleLimits limits = new BundleLimits(
        5, 1024, 1024, 1024, 10, java.time.Duration.ofSeconds(30));

    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolver("case-documents"))
        .destination(destination())
        .limits(limits)
        .build();

    assertThat(renderer.limits()).isEqualTo(limits);
  }

  @Test
  void rendererRejectsNullArguments() {
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolver("case-documents"))
        .destination(destination())
        .build();

    assertThatThrownBy(() -> renderer.render(null, BundleExecutionContext.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("request");
  }
}
