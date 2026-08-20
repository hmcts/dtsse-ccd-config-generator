package uk.gov.hmcts.ccd.sdk.bundling.pdf;

/**
 * One node of the ordered assembly tree: either an {@link AssemblyFolder} or an
 * {@link AssemblyItem}.
 */
public sealed interface AssemblyNode permits AssemblyFolder, AssemblyItem {

  /**
   * The display title of this node, rendered in the table of contents, cover sheets and
   * bookmarks.
   *
   * @return the node title
   */
  String title();
}
