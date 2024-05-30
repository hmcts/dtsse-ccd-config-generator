package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class SearchParty {

  private String searchPartyCollectionFieldName;
  private String searchPartyName;
  private String searchPartyEmailAddress;
  private String searchPartyAddressLine1;
  private String searchPartyPostCode;
  private String searchPartyDOB;
  private String searchPartyDOD;

  public static class SearchPartyBuilder {

    public static SearchPartyBuilder builder() {
      return SearchParty.builder();
    }

    public SearchPartyBuilder searchPartyCollectionFieldName(String searchPartyCollectionFieldName) {
      this.searchPartyCollectionFieldName = searchPartyCollectionFieldName;
      return this;
    }

    public SearchPartyBuilder searchPartyName(String searchPartyName) {
      this.searchPartyName = searchPartyName;
      return this;
    }

    public SearchPartyBuilder searchPartyEmailAddress(String searchPartyEmailAddress) {
      this.searchPartyEmailAddress = searchPartyEmailAddress;
      return this;
    }

    public SearchPartyBuilder searchPartyAddressLine1(String searchPartyAddressLine1) {
      this.searchPartyAddressLine1 = searchPartyAddressLine1;
      return this;
    }

    public SearchPartyBuilder searchPartyPostCode(String searchPartyPostCode) {
      this.searchPartyPostCode = searchPartyPostCode;
      return this;
    }

    public SearchPartyBuilder searchPartyDOB(String searchPartyDOB) {
      this.searchPartyDOB = searchPartyDOB;
      return this;
    }

    public SearchPartyBuilder searchPartyDOD(String searchPartyDOD) {
      this.searchPartyDOD = searchPartyDOD;
      return this;
    }
  }
}
