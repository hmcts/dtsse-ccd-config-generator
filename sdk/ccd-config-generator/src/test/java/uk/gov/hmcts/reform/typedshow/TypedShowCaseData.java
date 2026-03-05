package uk.gov.hmcts.reform.typedshow;

import java.util.List;
import uk.gov.hmcts.ccd.sdk.type.ListValue;

public class TypedShowCaseData {

  private TypedShowVisibility visibility;
  private String aField;
  private String title;
  private String notes;
  private String readonlyField;
  private String tags;
  private String category;
  private List<ListValue<TypedShowItem>> items;
  private TypedShowDetails details;

  public TypedShowVisibility getVisibility() {
    return visibility;
  }

  public String getAField() {
    return aField;
  }

  public String getTitle() {
    return title;
  }

  public String getNotes() {
    return notes;
  }

  public String getReadonlyField() {
    return readonlyField;
  }

  public String getTags() {
    return tags;
  }

  public String getCategory() {
    return category;
  }

  public List<ListValue<TypedShowItem>> getItems() {
    return items;
  }

  public TypedShowDetails getDetails() {
    return details;
  }
}
