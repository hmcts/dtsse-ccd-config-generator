package uk.gov.hmcts.ccd.sdk.bundling.render;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.fixture;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtension;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtensionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.convert.FileBackedSource;
import uk.gov.hmcts.ccd.sdk.bundling.testing.FilesystemBundleDestination;

/**
 * The bounded render permit: with two permits, a third concurrent render blocks until one frees,
 * and no more than two renders are ever inside the pipeline at once.
 */
class DefaultBundleRendererConcurrencyTest {

  @TempDir
  Path work;

  @TempDir
  Path published;

  @Test
  @Timeout(30)
  void aThirdRenderBlocksUntilAPermitFrees() throws Exception {
    CountDownLatch twoEntered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger entered = new AtomicInteger();
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maxActive = new AtomicInteger();

    BundlingExtension gatedPdfHandler = new BundlingExtension() {
      @Override
      public String name() {
        return "gated-pdf";
      }

      @Override
      public void configure(BundlingExtensionContext context) {
        context.replaceHandler("application/pdf", (source, ctx) -> {
          entered.incrementAndGet();
          int now = active.incrementAndGet();
          maxActive.accumulateAndGet(now, Math::max);
          twoEntered.countDown();
          try {
            release.await(20, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            active.decrementAndGet();
          }
          return HandledDocument.of(((FileBackedSource) source).file());
        });
      }
    };

    RenderTestSupport.InMemoryResolver resolver = new RenderTestSupport.InMemoryResolver()
        .source("good", RenderTestSupport.Source.of(
            fixture("one-page.pdf"), "application/pdf", "good.pdf"));
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolver)
        .destination(new FilesystemBundleDestination(published))
        .tempDirectory(work)
        .maxConcurrentRenders(2)
        .extension(gatedPdfHandler)
        .build();

    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      List<Future<BundleResult>> futures = List.of(
          submitRender(executor, renderer, "one"),
          submitRender(executor, renderer, "two"),
          submitRender(executor, renderer, "three"));

      // Two renders make it into the pipeline; the third blocks on the permit.
      assertThat(twoEntered.await(15, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(500);
      assertThat(entered).hasValue(2);

      release.countDown();
      for (Future<BundleResult> future : futures) {
        assertThat(future.get(20, TimeUnit.SECONDS)).isNotNull();
      }
      assertThat(entered).hasValue(3);
      assertThat(maxActive).hasValueLessThanOrEqualTo(2);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  private Future<BundleResult> submitRender(
      ExecutorService executor, BundleRenderer renderer, String name) {
    return executor.submit(() -> renderer.render(
        uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest.builder()
            .externalId(java.util.UUID.randomUUID())
            .title("Bundle " + name)
            .fileName("bundle-" + name + ".pdf")
            .root(uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection.builder("Case file")
                .document(RenderTestSupport.doc("doc-" + name, "Document " + name, "good"))
                .build())
            .build(),
        BundleExecutionContext.empty()));
  }
}
