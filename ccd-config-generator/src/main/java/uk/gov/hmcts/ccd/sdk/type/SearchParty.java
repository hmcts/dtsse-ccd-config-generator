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
@ComplexType(name = "SearchParty")
public class SearchParty {

  @JsonProperty("SearchPartyCollectionFieldName")
  private String collectionFieldName;

  @JsonProperty("SearchPartyName")
  private String name;

  @JsonProperty("SearchPartyEmailAddress")
  private String emailAddress;

  @JsonProperty("SearchPartyAddressLine1")
  private String addressLine1;

  @JsonProperty("SearchPartyPostCode")
  private String postcode;

  @JsonProperty("SearchPartyDOB")
  @JsonFormat(pattern = "yyyy-MM-dd")
  private LocalDate dateOfBirth;

  @JsonProperty("SearchPartyDOD")
  @JsonFormat(pattern = "yyyy-MM-dd")
  private LocalDate dateOfDeath;

  @JsonCreator
  public SearchParty(@JsonProperty("SearchPartyCollectionFieldName") String collectionFieldName,
                     @JsonProperty("SearchPartyName") String name,
                     @JsonProperty("SearchPartyEmailAddress") String emailAddress,
                     @JsonProperty("SearchPartyAddressLine1") String addressLine1,
                     @JsonProperty("SearchPartyPostcode") String postcode,
                     @JsonProperty("SearchPartyDOB") LocalDate dateOfBirth,
                     @JsonProperty("SearchPartyDOD") LocalDate dateOfDeath) {
    this.collectionFieldName = collectionFieldName;
    this.name = name;
    this.emailAddress = emailAddress;
    this.addressLine1 = addressLine1;
    this.postcode = postcode;
    this.dateOfBirth = dateOfBirth;
    this.dateOfDeath = dateOfDeath;
  }

}
