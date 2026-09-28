package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * Predefined CCD complex type shipped platform-side (definition-store base type
 * {@code JudicialUser}), holding a reference to a judicial office holder resolved via Reference
 * Data's Judicial Reference Data API.
 */
@NoArgsConstructor
@Builder
@Data
@ComplexType(name = "JudicialUser", generate = false)
public class JudicialUser {

  @JsonProperty("idamId")
  private String idamId;

  @JsonProperty("personalCode")
  private String personalCode;

  @JsonCreator
  public JudicialUser(
      @JsonProperty("idamId") String idamId,
      @JsonProperty("personalCode") String personalCode
  ) {
    this.idamId = idamId;
    this.personalCode = personalCode;
  }
}
