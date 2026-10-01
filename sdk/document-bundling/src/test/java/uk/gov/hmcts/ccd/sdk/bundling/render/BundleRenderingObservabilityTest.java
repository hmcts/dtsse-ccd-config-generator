package uk.gov.hmcts.ccd.sdk.bundling.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.fixture;
import static uk.gov.hmcts.ccd.sdk.bundling.render.RenderTestSupport.request;

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
import org.slf4j.MDC;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason;

class BundleRenderingObservabilityTest {
  @TempDir
  Path work;

  private final ListAppender<ILoggingEvent> rendererEvents = new ListAppender<>();
  private final ListAppender<ILoggingEvent> resolutionEvents = new ListAppender<>();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
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
    MDC.clear();
  }

  @Test
  void successfulRenderReportsTimingsAndMetersAndRestoresTheCallersMdc() {
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(new RenderTestSupport.InMemoryResolver().source("good",
            RenderTestSupport.Source.of(fixture("one-page.pdf"), "application/pdf", "good.pdf")))
        .tempDirectory(work)
        .meterRegistry(registry)
        .build();
    MDC.put("externalId", "caller-owned");
    MDC.put("requestId", "r-1");

    try (BundleResult result = renderer.render(request(doc("d1", "Doc", "good")),
        BundleExecutionContext.empty())) {
      assertThat(result.timings()).containsKeys(BundleStage.VALIDATE, BundleStage.RESOLVE,
          BundleStage.CONVERT, BundleStage.ASSEMBLE);
    }

    assertThat(MDC.get("externalId")).isEqualTo("caller-owned");
    assertThat(MDC.get("requestId")).isEqualTo("r-1");
    assertThat(MDC.get("stage")).isNull();
    assertThat(registry.get("ccd.bundling.stage").tag("stage", "convert").timer().count())
        .isEqualTo(1);
    assertThat(registry.get("ccd.bundling.stage").tag("stage", "assemble").timer().count())
        .isEqualTo(1);
    assertThat(registry.get("ccd.bundling.documents").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("ccd.bundling.pages").counter().count()).isGreaterThan(1.0);
    assertThat(registry.get("ccd.bundling.bytes").counter().count()).isGreaterThan(0.0);
    assertThat(allErrorEvents()).isEmpty();
  }

  @Test
  void failureIsLoggedExactlyOnceWithTheExternalIdInTheMdcAndCountedByCode() {
    BundleRenderer renderer = BundleRenderer.builder()
        .resolver(new RenderTestSupport.InMemoryResolver()
            .failure("missing", ResolutionFailureReason.NOT_FOUND, "No document with this id"))
        .tempDirectory(work)
        .meterRegistry(registry)
        .build();
    BundleRequest request = request(doc("d1", "Missing", "missing"));

    assertThatThrownBy(() -> renderer.render(request, BundleExecutionContext.empty()))
        .isInstanceOf(BundleGenerationException.class);

    List<ILoggingEvent> errors = allErrorEvents();
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).getFormattedMessage())
        .contains("DOCUMENT_NOT_FOUND").contains("RESOLVE").contains("d1");
    assertThat(errors.get(0).getMDCPropertyMap())
        .containsEntry("externalId", request.externalId().toString());
    assertThat(MDC.get("externalId")).isNull();
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
