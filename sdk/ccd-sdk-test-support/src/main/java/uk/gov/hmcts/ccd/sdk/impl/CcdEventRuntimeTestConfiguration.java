package uk.gov.hmcts.ccd.sdk.impl;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.ccd.sdk.runtime.CallbackController;
import uk.gov.hmcts.ccd.sdk.runtime.CcdCallbackExecutor;

/** Runtime services and endpoints needed to submit events in a focused application test context. */
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
    CcdCallbackExecutor.class,
    SupplementaryDataService.class,
    ServicePersistenceController.class,
    CallbackController.class
})
public class CcdEventRuntimeTestConfiguration {
}
