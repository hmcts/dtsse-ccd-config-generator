package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;

record LegacyAboutToSubmitCallbackResponse(
    Map<String, JsonNode> data,
    String state,
    SecurityClassification securityClassification,
    List<String> errors,
    List<String> warnings
) {
}
