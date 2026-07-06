package uk.gov.hmcts.ccd.sdk.retention.client;

import java.util.Map;

public record CcdCaseDataExistenceResponse(
    Map<String, Boolean> results
) {
}
