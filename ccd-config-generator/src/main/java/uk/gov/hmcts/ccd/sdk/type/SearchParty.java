package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;


@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "Search Party", generate = false)
public class SearchParty {
  @JsonProperty("Name")
  private String partyName;

  @JsonProperty("EmailAddress")
  private String partyEmailAddress;

  @JsonProperty("AddressLine1")
  private String partyAddressLine1;

  @JsonProperty("PostCode")
  private String partyPostcode;

  @JsonProperty("DOB")
  @JsonFormat(pattern = "yyyy-MM-dd")
  private LocalDate partyDateOfBirth;

  @JsonProperty("DOD")
  @JsonFormat(pattern = "yyyy-MM-dd")
  private LocalDate partyDateOfDeath;

  @JsonCreator
  public SearchParty(@JsonProperty("Name") String partyName,
                     @JsonProperty("EmailAddress") String partyEmailAddress,
                     @JsonProperty("AddressLine1") String partyAddressLine1,
                     @JsonProperty("Postcode") String partyPostcode,
                     @JsonProperty("DOB") LocalDate partyDateOfBirth,
                     @JsonProperty("DOD") LocalDate partyDateOfDeath) {
    this.partyName = partyName;
    this.partyEmailAddress = partyEmailAddress;
    this.partyAddressLine1 = partyAddressLine1;
    this.partyPostcode = partyPostcode;
    this.partyDateOfBirth = partyDateOfBirth;
    this.partyDateOfDeath = partyDateOfDeath;

  }
}
