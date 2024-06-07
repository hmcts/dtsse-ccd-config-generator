package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.serializer.LocalDateTimeSerializer;

@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "PreviousOrganisation", generate = false)
public class PreviousOrganisation {

  @JsonProperty("FromTimestamp")
  @JsonSerialize(using = LocalDateTimeSerializer.class)
  @JsonDeserialize(using = LocalDateTimeDeserializer.class)
  private LocalDateTime fromTimeStamp;

  @JsonProperty("ToTimestamp")
  @JsonSerialize(using = LocalDateTimeSerializer.class)
  @JsonDeserialize(using = LocalDateTimeDeserializer.class)
  private LocalDateTime toTimeStamp;

  @JsonProperty("OrganisationName")
  private String organisationName;

  @JsonProperty("OrganisationAddress")
  private AddressUK organisationAddress;

  @JsonCreator
  public PreviousOrganisation(
      @JsonProperty("FromTimestamp") LocalDateTime fromTimeStamp,
      @JsonProperty("ToTimestamp") LocalDateTime toTimeStamp,
      @JsonProperty("OrganisationName") String organisationName,
      @JsonProperty("OrganisationAddress") AddressUK organisationAddress
  ) {
    this.fromTimeStamp = fromTimeStamp;
    this.toTimeStamp = toTimeStamp;
    this.organisationName = organisationName;
    this.organisationAddress = organisationAddress;
  }
}
