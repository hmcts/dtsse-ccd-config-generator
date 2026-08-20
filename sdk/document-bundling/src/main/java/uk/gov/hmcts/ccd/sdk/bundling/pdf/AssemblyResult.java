package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.nio.file.Path;
import java.util.List;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;

/**
 * The outcome of one successful assembly: the produced PDF, its total page count, where each item
 * landed, and any non-fatal presentational warnings.
 *
 * @param outputPdf the produced PDF, under the working directory passed to the assembler
 * @param totalPages the total page count of the produced PDF
 * @param items each item's placement, in render order
 * @param warnings non-fatal presentational warnings, for example an included empty-section page
 *     or a replaced structure tree
 */
public record AssemblyResult(
    Path outputPdf,
    int totalPages,
    List<AssembledItem> items,
    List<BundleWarning> warnings) {

  /**
   * Validates the result and defensively copies the lists.
   *
   * @param outputPdf the produced PDF
   * @param totalPages the total page count
   * @param items each item's placement in render order
   * @param warnings non-fatal presentational warnings
   */
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
