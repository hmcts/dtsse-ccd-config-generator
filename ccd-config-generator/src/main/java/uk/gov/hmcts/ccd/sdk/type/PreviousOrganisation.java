package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "PreviousOrganisation", generate = false)
public class PreviousOrganisation {

  @JsonProperty("FromTimeStamp")
  @JsonSerialize(using = LocalDateTimeSerializer.class)
  @JsonDeserialize(using = LocalDateTimeDeserializer.class)
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS",
          with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES
  )
  private LocalDateTime fromTimeStamp;

  @JsonProperty("ToTimeStamp")
  @JsonSerialize(using = LocalDateTimeSerializer.class)
  @JsonDeserialize(using = LocalDateTimeDeserializer.class)
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS",
          with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
  private LocalDateTime toTimeStamp;

  @JsonProperty("OrganisationName")
  private String organisationName;

  @JsonProperty("OrganisationAddress")
  private AddressUK organisationAddress;

  @JsonCreator
  public PreviousOrganisation(
      @JsonProperty("FromTimeStamp") LocalDateTime fromTimeStamp,
      @JsonProperty("ToTimeStamp") LocalDateTime toTimeStamp,
      @JsonProperty("OrganisationName") String organisationName,
      @JsonProperty("OrganisationAddress") AddressUK organisationAddress
  ) {
    this.fromTimeStamp = fromTimeStamp;
    this.toTimeStamp = toTimeStamp;
    this.organisationName = organisationName;
    this.organisationAddress = organisationAddress;
  }
}
