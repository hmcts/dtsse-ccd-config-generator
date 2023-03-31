package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class CaseCategory<R extends HasRole> {
  private R role;
  private String categoryID;
  private String categoryLabel;
  private int displayOrder;
  private String parentCategoryID;

  public static class CaseCategoryBuilder<R extends HasRole> {

    public static <R extends HasRole> CaseCategoryBuilder<R> builder(R role) {
      CaseCategoryBuilder<R> result = CaseCategory.builder();
      result.role = role;
      result.categoryID = null;
      result.categoryLabel = null;
      result.displayOrder = 0;
      result.parentCategoryID = null;
      return result;
    }

    public CaseCategoryBuilder<R> categoryID(String categoryID) {
      this.categoryID = categoryID;
      return this;
    }

    public CaseCategoryBuilder<R> categoryLabel(String categoryLabel) {
      this.categoryLabel = categoryLabel;
      return this;
    }

    public CaseCategoryBuilder<R> displayOrder(int displayOrder) {
      this.displayOrder = displayOrder;
      return this;
    }

    public CaseCategoryBuilder<R> parentCategoryID(String parentCategoryID) {
      this.parentCategoryID = parentCategoryID;
      return this;
    }

  }
}
