package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;

/**
 * Everything the assembler needs to build one bundle PDF: the bundle metadata, the presentation
 * preset, and the ordered tree of folders and already-converted items. This is the hand-over
 * shape between the rendering pipeline (which validates, resolves and converts) and the PDF
 * assembly layer; it is deliberately decoupled from the public request model and from the current
 * stitching service's JPA entities.
 *
 * @param bundleTitle the bundle title, used for the root bookmark and the generated title page
 * @param outputFileName the file name of the produced PDF, created under the working directory;
 *     a plain name without path separators
 * @param description an optional description rendered at the top of the table of contents,
 *     mirroring the current service's index page
 * @param presentation the approved presentation preset
 * @param titlePage whether to prepend the generated bundle title page
 * @param watermark an optional approved watermark applied to every source document's pages
 * @param items the ordered tree of folders and items
 */
public record AssemblyRequest(
    String bundleTitle,
    String outputFileName,
    Optional<String> description,
    BundlePresentation presentation,
    boolean titlePage,
    Optional<Watermark> watermark,
    List<AssemblyNode> items) {

  /**
   * Validates the request and defensively copies the item tree.
   *
   * @param bundleTitle the bundle title
   * @param outputFileName the output file name
   * @param description the optional table-of-contents description
   * @param presentation the approved presentation preset
   * @param titlePage whether to prepend the generated title page
   * @param watermark the optional approved watermark
   * @param items the ordered tree of folders and items
   */
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
    Checks.requireNonNull(watermark, "AssemblyRequest.watermark");
    Checks.requireNonNull(items, "AssemblyRequest.items");
    items = List.copyOf(items);
  }

  /**
   * A minimal request without description, title page or watermark.
   *
   * @param bundleTitle the bundle title
   * @param outputFileName the output file name
   * @param presentation the approved presentation preset
   * @param items the ordered tree of folders and items
   * @return the request
   */
  public static AssemblyRequest of(
      String bundleTitle,
      String outputFileName,
      BundlePresentation presentation,
      List<AssemblyNode> items) {
    return new AssemblyRequest(
        bundleTitle, outputFileName, Optional.empty(), presentation, false, Optional.empty(),
        items);
  }
}
