package uk.gov.hmcts.ccd.sdk;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Data
public class SupplementaryDataUpdateRequest {

    @JsonProperty("supplementary_data_updates")
    private Map<String, Map<String, Object>> requestData;

}

