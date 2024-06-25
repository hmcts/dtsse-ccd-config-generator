package uk.gov.hmcts.ccd.sdk.api;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class SearchCriteria {

  private List<SearchCriteriaField> fields;

  public static class SearchCriteriaBuilder {

    public static SearchCriteriaBuilder builder() {
      return SearchCriteria.builder();
    }

    public SearchCriteriaBuilder fields(List<SearchCriteriaField> fields) {
      this.fields = fields;
      return this;
    }
  }
}
