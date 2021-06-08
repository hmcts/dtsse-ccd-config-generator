package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@SuperBuilder
@Data
@ComplexType(name = "Address", generate = false)
public class Address {

  @JsonProperty("AddressLine1")
  protected String addressLine1;

  @JsonProperty("AddressLine2")
  protected String addressLine2;

  @JsonProperty("AddressLine3")
  protected String addressLine3;

  @JsonProperty("PostTown")
  protected String postTown;

  @JsonProperty("County")
  protected String county;

  @JsonProperty("PostCode")
  protected String postCode;

  @JsonProperty("Country")
  protected String country;

  @JsonCreator
  public Address(
      @JsonProperty("AddressLine1") String addressLine1,
      @JsonProperty("AddressLine2") String addressLine2,
      @JsonProperty("AddressLine3") String addressLine3,
      @JsonProperty("PostTown") String postTown,
      @JsonProperty("County") String county,
      @JsonProperty("PostCode") String postCode,
      @JsonProperty("Country") String country
  ) {
    this.addressLine1 = addressLine1;
    this.addressLine2 = addressLine2;
    this.addressLine3 = addressLine3;
    this.postTown = postTown;
    this.county = county;
    this.postCode = postCode;
    this.country = country;
  }
}
