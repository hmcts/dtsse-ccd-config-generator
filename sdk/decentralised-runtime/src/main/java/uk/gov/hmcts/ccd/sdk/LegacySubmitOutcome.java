package uk.gov.hmcts.ccd.sdk;

import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedSubmitEventResponse;

public record LegacySubmitOutcome(DecentralisedSubmitEventResponse response,
                                   boolean runSubmittedCallback) {}
