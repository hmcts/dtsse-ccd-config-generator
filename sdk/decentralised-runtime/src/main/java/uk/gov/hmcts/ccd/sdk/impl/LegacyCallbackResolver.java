package uk.gov.hmcts.ccd.sdk.impl;

import java.util.Optional;

interface LegacyCallbackResolver {

  Optional<LegacyCallback> resolve(String caseTypeId, String eventId);
}
