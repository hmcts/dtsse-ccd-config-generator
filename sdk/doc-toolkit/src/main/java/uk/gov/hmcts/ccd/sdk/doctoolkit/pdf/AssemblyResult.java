package uk.gov.hmcts.ccd.sdk.doctoolkit.pdf;

import java.nio.file.Path;
import java.util.List;

public record AssemblyResult(
    Path outputPdf,
    int totalPages,
    List<AssembledItem> items) {
  public AssemblyResult {
    Checks.requireNonNull(outputPdf, "AssemblyResult.outputPdf");
    if (totalPages < 1) {
      throw new IllegalArgumentException("AssemblyResult.totalPages must be positive");
    }
    Checks.requireNonNull(items, "AssemblyResult.items");
    items = List.copyOf(items);
  }
}
