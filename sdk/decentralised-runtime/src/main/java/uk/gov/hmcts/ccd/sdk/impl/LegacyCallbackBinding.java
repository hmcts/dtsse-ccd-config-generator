package uk.gov.hmcts.ccd.sdk.impl;

record LegacyCallbackBinding(
    String caseTypeId,
    String eventId,
    LegacyCallbackType callbackType
) {
}
