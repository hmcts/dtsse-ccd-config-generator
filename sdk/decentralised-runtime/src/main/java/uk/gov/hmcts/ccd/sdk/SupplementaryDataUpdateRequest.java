package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Data;

@Data
public class SupplementaryDataUpdateRequest {

  @JsonProperty("supplementary_data_updates")
  private Map<String, Map<String, Object>> requestData;

}
