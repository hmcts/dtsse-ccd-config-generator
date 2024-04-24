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
@ComplexType(name = "PreviousOrganisationCollectionItem", generate = false)
public class PreviousOrganisationCollectionItem {

  @JsonProperty("id")
  private String id;

  @JsonProperty("value")
  private PreviousOrganisation value;

  @JsonCreator
  public PreviousOrganisationCollectionItem(
      @JsonProperty("id") String id,
      @JsonProperty("value") PreviousOrganisation value
  ) {
    this.id = id;
    this.value = value;
  }
}
