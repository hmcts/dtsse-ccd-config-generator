package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.nio.file.Path;
import java.util.List;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;

public record AssemblyResult(
    Path outputPdf,
    int totalPages,
    List<AssembledItem> items,
    List<BundleWarning> warnings) {
  public AssemblyResult {
    Checks.requireNonNull(outputPdf, "AssemblyResult.outputPdf");
    if (totalPages < 1) {
      throw new IllegalArgumentException("AssemblyResult.totalPages must be positive");
    }
    Checks.requireNonNull(items, "AssemblyResult.items");
    Checks.requireNonNull(warnings, "AssemblyResult.warnings");
    items = List.copyOf(items);
    warnings = List.copyOf(warnings);
  }
}
