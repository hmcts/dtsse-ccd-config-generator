package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class SearchCriteria {

  private String otherCaseReference;

  public static class SearchCriteriaBuilder {

    public static SearchCriteriaBuilder builder() {
      return SearchCriteria.builder();
    }

    public SearchCriteriaBuilder otherCaseReference(String otherCaseReference) {
      this.otherCaseReference = otherCaseReference;
      return this;
    }
  }
}
