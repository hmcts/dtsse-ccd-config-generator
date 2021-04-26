package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@AllArgsConstructor
@Builder
@Data
@Jacksonized
@ComplexType(name = "PreviousOrganisation", generate = false)
public class PreviousOrganisation {

  @JsonProperty("FromTimeStamp")
  @JsonSerialize(using = LocalDateTimeSerializer.class)
  @JsonDeserialize(using = LocalDateTimeDeserializer.class)
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
  private final LocalDateTime fromTimeStamp;

  @JsonProperty("ToTimeStamp")
  @JsonSerialize(using = LocalDateTimeSerializer.class)
  @JsonDeserialize(using = LocalDateTimeDeserializer.class)
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
  private final LocalDateTime toTimeStamp;

  @JsonProperty("OrganisationName")
  private final String organisationName;

  @JsonProperty("OrganisationAddress")
  private final String organisationAddress;
}
