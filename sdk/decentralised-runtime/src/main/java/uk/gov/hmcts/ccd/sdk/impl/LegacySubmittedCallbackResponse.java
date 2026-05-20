package uk.gov.hmcts.ccd.sdk.impl;

record LegacySubmittedCallbackResponse(
    String confirmationHeader,
    String confirmationBody
) {
}
