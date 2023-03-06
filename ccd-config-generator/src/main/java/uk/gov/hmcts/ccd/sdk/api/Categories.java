package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class Categories<R extends HasRole> {
  private R role;
  private String categoryID;
  private String categoryLabel;
  private int displayOrder;
  private String parentCategoryID;

  public static class CategoriesBuilder<R extends HasRole> {

    public static <R extends HasRole> CategoriesBuilder<R> builder(R role) {
      CategoriesBuilder<R> result = Categories.builder();
      result.role = role;
      result.categoryID = null;
      result.categoryLabel = null;
      result.displayOrder = 0;
      result.parentCategoryID = null;
      return result;
    }

    public CategoriesBuilder<R> categoryID(String categoryID) {
      this.categoryID = categoryID;
      return this;
    }

    public CategoriesBuilder<R> categoryLabel(String categoryLabel) {
      this.categoryLabel = categoryLabel;
      return this;
    }

    public CategoriesBuilder<R> displayOrder(int displayOrder) {
      this.displayOrder = displayOrder;
      return this;
    }

    public CategoriesBuilder<R> parentCategoryID(String parentCategoryID) {
      this.parentCategoryID = parentCategoryID;
      return this;
    }

  }
}
