package uk.gov.hmcts.ccd.sdk.api;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class SearchParty {
  private List<SearchPartyField> fields;

  public static class SearchPartyBuilder {

    public static SearchPartyBuilder builder() {
      SearchPartyBuilder result = SearchParty.builder();
      result.fields = new ArrayList<>();
      return result;
    }
  }
}
