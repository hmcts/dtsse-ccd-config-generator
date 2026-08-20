package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;

/**
 * The typed {@link BundleErrorCode#TIMED_OUT} failure: the hard end-to-end deadline elapsed.
 *
 * <p>Alongside the standard typed fields, it carries the per-stage timings gathered up to the
 * moment the deadline fired as data, not just message text, so a durable worker or dashboard can
 * read where the time went without parsing. The entry for the stage that was in flight when the
 * timeout surfaced covers only its elapsed portion.
 */
public final class BundleRenderTimeoutException extends BundleGenerationException {

  private final transient Map<BundleStage, Duration> timingsSoFar;

  /**
   * Creates the timeout failure.
   *
   * @param stage the stage in flight when the deadline fired
   * @param message what timed out, including the readable timings
   * @param remediation what to do about it
   * @param timingsSoFar elapsed time per stage up to the timeout, the in-flight stage included
   */
  public BundleRenderTimeoutException(BundleStage stage, String message, String remediation,
      Map<BundleStage, Duration> timingsSoFar) {
    super(BundleErrorCode.TIMED_OUT, stage, message, remediation, List.of());
    this.timingsSoFar = Map.copyOf(timingsSoFar);
  }

  /**
   * Elapsed time per pipeline stage up to the moment the deadline fired, including the elapsed
   * portion of the stage that was in flight.
   *
   * @return the immutable per-stage timings
   */
  public Map<BundleStage, Duration> timingsSoFar() {
    return timingsSoFar;
  }
}
