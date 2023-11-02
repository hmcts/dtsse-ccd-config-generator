package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Builder
@Data
public class SearchParty<R extends HasRole> {

  private R role;
  private String name;
  private String emailAddress;
  private String addressLine1;
  private String postcode;
  private String dateOfBirth;
  private String dateOfDeath;

  public static class SearchPartyBuilder<R extends HasRole> {

    public static <R extends HasRole> SearchPartyBuilder<R> builder(R role) {
      SearchPartyBuilder<R> result = SearchParty.builder();
      result.role = role;
      result.name = null;
      result.emailAddress = null;
      result.addressLine1 = null;
      result.postcode = null;
      result.dateOfBirth = null;
      result.dateOfDeath = null;
      return result;
    }

    public SearchPartyBuilder<R> name(String name) {
      this.name = name;
      return this;
    }

    public SearchPartyBuilder<R> emailAddress(String emailAddress) {
      this.emailAddress = emailAddress;
      return this;
    }

    public SearchPartyBuilder<R> addressLine1(String addressLine1) {
      this.addressLine1 = addressLine1;
      return this;
    }

    public SearchPartyBuilder<R> postcode(String postcode) {
      this.postcode = postcode;
      return this;
    }

    public SearchPartyBuilder<R> dateOfBirth(String dateOfBirth) {
      this.dateOfBirth = dateOfBirth;
      return this;
    }

    public SearchPartyBuilder<R> dateOfDeath(String dateOfDeath) {
      this.dateOfDeath = dateOfDeath;
      return this;
    }
  }

}
