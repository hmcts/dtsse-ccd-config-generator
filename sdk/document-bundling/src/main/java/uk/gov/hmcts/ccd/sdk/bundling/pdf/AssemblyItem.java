package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.time.LocalDate;
import java.util.Optional;

/**
 * One leaf entry in the assembly tree: a converted source PDF, a generated media link page, or a
 * generated empty-section placeholder page. Every item participates in the table of contents,
 * bookmarks, pagination and confidential marking in the same way.
 *
 * @param title the display title, rendered in the contents, cover sheet and bookmark
 * @param date the supplied document date, rendered in the table of contents when present
 * @param confidential whether the item's pages receive the approved confidential marking when the
 *     presentation enables it
 * @param content what the item renders as
 */
public record AssemblyItem(
    String title,
    Optional<LocalDate> date,
    boolean confidential,
    AssemblyContent content) implements AssemblyNode {

  /**
   * Validates the item.
   *
   * @param title the display title
   * @param date the optional document date
   * @param confidential the confidential flag
   * @param content the item content
   */
  public AssemblyItem {
    Checks.requireNonBlank(title, "AssemblyItem.title");
    Checks.requireNonNull(date, "AssemblyItem.date");
    Checks.requireNonNull(content, "AssemblyItem.content");
  }
}
