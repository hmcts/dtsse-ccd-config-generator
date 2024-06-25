package uk.gov.hmcts.ccd.sdk.api;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class SearchParty {

  private List<SearchPartyField> fields;

  public static class SearchPartyBuilder {

    public static SearchPartyBuilder builder() {
      return SearchParty.builder();
    }

    public SearchPartyBuilder fields(List<SearchPartyField> fields) {
      this.fields = fields;
      return this;
    }
  }

}
