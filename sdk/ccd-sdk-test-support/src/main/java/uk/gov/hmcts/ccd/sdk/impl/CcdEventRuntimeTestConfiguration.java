package uk.gov.hmcts.ccd.sdk.impl;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.ccd.sdk.runtime.CcdCallbackExecutor;

/** Runtime services needed to submit events in a focused application test context. */
@TestConfiguration(proxyBeanMethods = false)
@Import({
    CaseSubmissionService.class,
    DecentralisedSubmissionHandler.class,
    LegacyCallbackSubmissionHandler.class,
    CaseEventTransactionCoordinator.class,
    IdempotencyEnforcer.class,
    AuditEventService.class,
    CaseDataRepository.class,
    CaseProjectionService.class,
    DefinitionRegistry.class,
    CcdCallbackExecutor.class
})
public class CcdEventRuntimeTestConfiguration {
}
