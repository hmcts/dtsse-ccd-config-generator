package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A bundle generation failure. Nothing was published.
 *
 * <p>The message alone must let a service developer reading it in their own Application Insights
 * tell what failed, on which document(s), at which stage, and what to do next: it carries the
 * stable {@link BundleErrorCode}, the {@link BundleStage}, every responsible document, and a
 * remediation hint.
 */
public class BundleGenerationException extends RuntimeException {

  private final BundleErrorCode code;
  private final BundleStage stage;
  private final List<DocumentFailure> documentFailures;
  private final String remediation;

  /**
   * Creates a failure without a cause.
   *
   * @param code the stable error code
   * @param stage the pipeline stage that failed
   * @param message what failed
   * @param remediation what to do about it
   * @param documentFailures the responsible documents; empty when the failure is bundle-level
   */
  public BundleGenerationException(BundleErrorCode code, BundleStage stage, String message,
      String remediation, List<DocumentFailure> documentFailures) {
    this(code, stage, message, remediation, documentFailures, null);
  }

  /**
   * Creates a failure with a cause.
   *
   * @param code the stable error code
   * @param stage the pipeline stage that failed
   * @param message what failed
   * @param remediation what to do about it
   * @param documentFailures the responsible documents; empty when the failure is bundle-level
   * @param cause the underlying cause, if any
   */
  public BundleGenerationException(BundleErrorCode code, BundleStage stage, String message,
      String remediation, List<DocumentFailure> documentFailures, Throwable cause) {
    super(compose(code, stage, message, remediation, documentFailures), cause);
    this.code = Validate.requireNonNull(code, "BundleGenerationException.code");
    this.stage = Validate.requireNonNull(stage, "BundleGenerationException.stage");
    this.remediation = remediation;
    this.documentFailures = List.copyOf(
        Validate.requireNonNull(documentFailures, "BundleGenerationException.documentFailures"));
  }

  /**
   * The stable error code from the documented catalogue.
   *
   * @return the error code
   */
  public BundleErrorCode code() {
    return code;
  }

  /**
   * The pipeline stage that failed.
   *
   * @return the stage
   */
  public BundleStage stage() {
    return stage;
  }

  /**
   * The documents responsible for the failure, each with its own typed reason.
   *
   * @return the immutable document failure list; empty when the failure is bundle-level
   */
  public List<DocumentFailure> documentFailures() {
    return documentFailures;
  }

  /**
   * The remediation hint.
   *
   * @return the remediation hint, or null when none applies
   */
  public String remediation() {
    return remediation;
  }

  private static String compose(BundleErrorCode code, BundleStage stage, String message,
      String remediation, List<DocumentFailure> documentFailures) {
    StringBuilder text = new StringBuilder()
        .append(code)
        .append(" at stage ")
        .append(stage)
        .append(": ")
        .append(message);
    if (documentFailures != null && !documentFailures.isEmpty()) {
      text.append(" Failed documents: ")
          .append(documentFailures.stream()
              .map(DocumentFailure::describe)
              .collect(Collectors.joining("; ")))
          .append('.');
    }
    if (remediation != null && !remediation.isBlank()) {
      text.append(" Remediation: ").append(remediation);
    }
    return text.toString();
  }
}
