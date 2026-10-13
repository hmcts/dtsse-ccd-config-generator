package uk.gov.hmcts.ccd.sdk.bundling.pdf;

public record AssembledItem(String title, int startPage, int pageCount) {
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
