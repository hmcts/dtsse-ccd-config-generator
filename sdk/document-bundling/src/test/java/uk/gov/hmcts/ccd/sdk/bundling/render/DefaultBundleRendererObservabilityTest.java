package uk.gov.hmcts.ccd.sdk.bundling.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.fixture;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason;
import uk.gov.hmcts.ccd.sdk.bundling.testing.FilesystemBundleDestination;

/**
 * The observability contract: a failure is logged exactly once, at the point of final failure,
 * with the external id in the MDC; and the optional Micrometer registry receives the
 * ccd.bundling.* meters.
 */
class DefaultBundleRendererObservabilityTest {

  @TempDir
  Path work;

  @TempDir
  Path published;

  private final ListAppender<ILoggingEvent> rendererEvents = new ListAppender<>();
  private final ListAppender<ILoggingEvent> resolutionEvents = new ListAppender<>();
  private Logger rendererLogger;
  private Logger resolutionLogger;

  @BeforeEach
  void attachAppenders() {
    rendererLogger = (Logger) LoggerFactory.getLogger(DefaultBundleRenderer.class);
    resolutionLogger = (Logger) LoggerFactory.getLogger(Resolution.class);
    rendererEvents.start();
    resolutionEvents.start();
    rendererLogger.addAppender(rendererEvents);
    resolutionLogger.addAppender(resolutionEvents);
  }

  @AfterEach
  void detachAppenders() {
    rendererLogger.detachAppender(rendererEvents);
    resolutionLogger.detachAppender(resolutionEvents);
  }

  @Test
  void aFailureIsLoggedExactlyOnceWithTheExternalIdInTheMdc() {
    RenderTestSupport.InMemoryResolver resolver = new RenderTestSupport.InMemoryResolver()
        .failure("missing", new ResolutionFailure(
            ResolutionFailureReason.NOT_FOUND, "No document with this id"));
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolver)
        .destination(new FilesystemBundleDestination(published))
        .tempDirectory(work)
        .build();
    BundleRequest request = RenderTestSupport.singleDocumentRequest(
        RenderTestSupport.doc("d1", "Missing", "missing"));

    assertThatThrownBy(() -> renderer.render(request, BundleExecutionContext.empty()))
        .isInstanceOf(BundleGenerationException.class);

    List<ILoggingEvent> errors = allErrorEvents();
    assertThat(errors).hasSize(1);
    ILoggingEvent error = errors.get(0);
    assertThat(error.getFormattedMessage())
        .contains("DOCUMENT_NOT_FOUND")
        .contains("RESOLVE")
        .contains("d1");
    assertThat(error.getMDCPropertyMap())
        .containsEntry("externalId", request.externalId().toString());
  }

  @Test
  void metricsArePublishedToTheConfiguredRegistry() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RenderTestSupport.InMemoryResolver resolver = new RenderTestSupport.InMemoryResolver()
        .source("good", RenderTestSupport.Source.of(
            fixture("one-page.pdf"), "application/pdf", "good.pdf"));
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolver)
        .destination(new FilesystemBundleDestination(published))
        .tempDirectory(work)
        .meterRegistry(registry)
        .build();

    renderer.render(
        RenderTestSupport.singleDocumentRequest(RenderTestSupport.doc("d1", "Doc", "good")),
        BundleExecutionContext.empty());

    assertThat(registry.get("ccd.bundling.stage").tag("stage", "convert").timer().count())
        .isEqualTo(1);
    assertThat(registry.get("ccd.bundling.stage").tag("stage", "store").timer().count())
        .isEqualTo(1);
    assertThat(registry.get("ccd.bundling.documents").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("ccd.bundling.pages").counter().count()).isGreaterThan(1.0);
    assertThat(registry.get("ccd.bundling.bytes").counter().count()).isGreaterThan(0.0);
  }

  @Test
  void aFailedRenderCountsAFailureTaggedByCode() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RenderTestSupport.InMemoryResolver resolver = new RenderTestSupport.InMemoryResolver()
        .failure("missing", new ResolutionFailure(
            ResolutionFailureReason.NOT_FOUND, "No document with this id"));
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(resolver)
        .destination(new FilesystemBundleDestination(published))
        .tempDirectory(work)
        .meterRegistry(registry)
        .build();

    assertThatThrownBy(() -> renderer.render(
        RenderTestSupport.singleDocumentRequest(
            RenderTestSupport.doc("d1", "Missing", "missing")),
        BundleExecutionContext.empty()))
        .isInstanceOf(BundleGenerationException.class);

    assertThat(registry.get("ccd.bundling.failures")
        .tag("code", "DOCUMENT_NOT_FOUND").counter().count()).isEqualTo(1.0);
  }

  private List<ILoggingEvent> allErrorEvents() {
    return java.util.stream.Stream.concat(
            rendererEvents.list.stream(), resolutionEvents.list.stream())
        .filter(event -> event.getLevel() == Level.ERROR)
        .toList();
  }
}
