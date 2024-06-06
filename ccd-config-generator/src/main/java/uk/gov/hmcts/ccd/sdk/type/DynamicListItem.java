package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@Data
@Builder
@ToString
public class DynamicListItem {

  @JsonProperty("code")
  private String code;

  @JsonProperty("label")
  private String label;

  public DynamicListItem(@JsonProperty("code") String code, @JsonProperty("label") String label) {
    this.code = code;
    this.label = label;
  }
}
