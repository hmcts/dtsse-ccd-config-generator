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

  @JsonProperty("CollectionFieldName")
  private String collectionFieldName;

  @JsonProperty("Name")
  private String name;

  @JsonProperty("EmailAddress")
  private String emailAddress;

  @JsonProperty("AddressLine1")
  private String addressLine1;

  @JsonProperty("PostCode")
  private String postcode;

  @JsonProperty("DOB")
  @JsonFormat(pattern = "yyyy-MM-dd")
  private LocalDate dateOfBirth;

  @JsonProperty("DOD")
  @JsonFormat(pattern = "yyyy-MM-dd")
  private LocalDate dateOfDeath;

  @JsonCreator
  public SearchParty(@JsonProperty("CollectionFieldName") String collectionFieldName,
                     @JsonProperty("Name") String name,
                     @JsonProperty("EmailAddress") String emailAddress,
                     @JsonProperty("AddressLine1") String addressLine1,
                     @JsonProperty("Postcode") String postcode,
                     @JsonProperty("DOB") LocalDate dateOfBirth,
                     @JsonProperty("DOD") LocalDate dateOfDeath) {
    this.collectionFieldName = collectionFieldName;
    this.name = name;
    this.emailAddress = emailAddress;
    this.addressLine1 = addressLine1;
    this.postcode = postcode;
    this.dateOfBirth = dateOfBirth;
    this.dateOfDeath = dateOfDeath;
  }

}
