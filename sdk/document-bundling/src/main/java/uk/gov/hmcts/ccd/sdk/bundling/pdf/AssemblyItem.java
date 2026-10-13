package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.time.LocalDate;
import java.util.Optional;

public record AssemblyItem(
    String title,
    Optional<LocalDate> date,
    boolean confidential,
    AssemblyContent content) implements AssemblyNode {
  public AssemblyItem {
    Checks.requireNonBlank(title, "AssemblyItem.title");
    Checks.requireNonNull(date, "AssemblyItem.date");
    Checks.requireNonNull(content, "AssemblyItem.content");
  }
}
