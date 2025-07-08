package uk.gov.hmcts.ccd.sdk;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class SupplementaryDataUpdateRequest {

    @JsonProperty("supplementary_data_updates")
    private final Map<String, Map<String, Object>> requestData;

}

