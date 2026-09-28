package uk.gov.hmcts.ccd.sdk.bundling.api;

import java.nio.file.Path;
import java.util.List;

/**
 * The PDF representation of one source document, produced by a {@link DocumentHandler}.
 */
public record HandledDocument(Path pdfFile, List<BundleWarning> warnings) {

  public HandledDocument {
    Validate.requireNonNull(pdfFile, "HandledDocument.pdfFile");
    warnings = List.copyOf(Validate.requireNonNull(warnings, "HandledDocument.warnings"));
  }

  /** Creates a handled document with no warnings. */
  public static HandledDocument of(Path pdfFile) {
    return new HandledDocument(pdfFile, List.of());
  }
}
