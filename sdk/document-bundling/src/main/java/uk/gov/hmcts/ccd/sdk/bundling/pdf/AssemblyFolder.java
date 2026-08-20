package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.util.List;

/**
 * A folder (section) in the assembly tree, containing an ordered list of child folders and items.
 *
 * <p>A folder that contains no renderable items at all — after the rendering pipeline has applied
 * its {@link uk.gov.hmcts.ccd.sdk.bundling.api.EmptySectionPolicy} — is skipped entirely by the
 * assembler: no cover sheet, no contents entry and no bookmark, matching the current stitching
 * service. A section kept under {@code INCLUDE_PLACEHOLDER} must instead carry an
 * {@link AssemblyItem} whose content is an {@link EmptySectionPage}.
 *
 * @param title the folder title
 * @param children the ordered child nodes
 */
public record AssemblyFolder(String title, List<AssemblyNode> children) implements AssemblyNode {

  /**
   * Validates and defensively copies the children.
   *
   * @param title the folder title
   * @param children the ordered child nodes
   */
  public AssemblyFolder {
    Checks.requireNonBlank(title, "AssemblyFolder.title");
    Checks.requireNonNull(children, "AssemblyFolder.children");
    children = List.copyOf(children);
  }
}
