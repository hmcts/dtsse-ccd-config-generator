package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;

public record AssemblyRequest(
    String bundleTitle,
    String outputFileName,
    Optional<String> description,
    BundlePresentation presentation,
    boolean titlePage,
    List<AssemblyNode> items) {
  public AssemblyRequest {
    Checks.requireNonBlank(bundleTitle, "AssemblyRequest.bundleTitle");
    Checks.requireNonBlank(outputFileName, "AssemblyRequest.outputFileName");
    try {
      // Validate at construction, not after the whole bundle has merged: the name must form a
      // legal single-element path (no NUL or other unmappable characters, no separators).
      if (Path.of(outputFileName).getNameCount() != 1
          || outputFileName.contains("/") || outputFileName.contains("\\")
          || outputFileName.contains("..")) {
        throw new IllegalArgumentException(
            "AssemblyRequest.outputFileName must be a plain file name: '" + outputFileName
                + "'");
      }
    } catch (InvalidPathException e) {
      throw new IllegalArgumentException(
          "AssemblyRequest.outputFileName must be a plain file name", e);
    }
    Checks.requireNonNull(description, "AssemblyRequest.description");
    Checks.requireNonNull(presentation, "AssemblyRequest.presentation");
    Checks.requireNonNull(items, "AssemblyRequest.items");
    items = List.copyOf(items);
  }

  public static AssemblyRequest of(
      String bundleTitle,
      String outputFileName,
      BundlePresentation presentation,
      List<AssemblyNode> items) {
    return new AssemblyRequest(
        bundleTitle, outputFileName, Optional.empty(), presentation, false, items);
  }
}
