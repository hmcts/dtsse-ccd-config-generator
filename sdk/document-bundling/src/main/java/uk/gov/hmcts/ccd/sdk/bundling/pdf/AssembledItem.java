package uk.gov.hmcts.ccd.sdk.bundling.pdf;

/**
 * Where one {@link AssemblyItem} landed in the finished bundle, in render order.
 *
 * <p>{@code startPage} is the 1-based number of the item's first content page — the page its
 * table-of-contents entry links to. {@code pageCount} counts the item's content pages only;
 * generated cover sheets belong to the presentation, not to the item.
 *
 * @param title the item title, as rendered
 * @param startPage the 1-based first content page of the item in the output PDF
 * @param pageCount the number of content pages the item contributed
 */
public record AssembledItem(String title, int startPage, int pageCount) {

  /**
   * Validates the placement.
   *
   * @param title the item title
   * @param startPage the 1-based first content page
   * @param pageCount the number of content pages
   */
  public AssembledItem {
    Checks.requireNonBlank(title, "AssembledItem.title");
    if (startPage < 1) {
      throw new IllegalArgumentException("AssembledItem.startPage must be 1-based and positive");
    }
    if (pageCount < 1) {
      throw new IllegalArgumentException("AssembledItem.pageCount must be positive");
    }
  }
}
