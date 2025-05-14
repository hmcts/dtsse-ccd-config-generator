package uk.gov.hmcts.ccd.sdk.api;

import org.springframework.util.MultiValueMap;

public record EventPayload<T, S>(long caseReference, T caseData, MultiValueMap<String, String> urlParams) {
}
