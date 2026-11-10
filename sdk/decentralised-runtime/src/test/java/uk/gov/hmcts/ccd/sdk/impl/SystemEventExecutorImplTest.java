package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.SystemEventResult;

class SystemEventExecutorImplTest {

  private static final long CASE_REFERENCE = 1234567890123456L;

  private final CaseEventTransactionCoordinator coordinator = mock(CaseEventTransactionCoordinator.class);
  private final CaseDataRepository caseDataRepository = mock(CaseDataRepository.class);
  private final IdamService idamService = mock(IdamService.class);

  @Test
  void recordsTheConfiguredSystemUserIdWithoutCallingIdam() {
    var executor = new SystemEventExecutorImpl(
        "configured-id", "system@example.com", "Case", "System", "",
        coordinator, caseDataRepository, idamService
    );
    when(caseDataRepository.getCase(CASE_REFERENCE)).thenReturn(existingCase());
    AtomicReference<CaseEventTransactionCoordinator.CaseEventWrite<?>> write = new AtomicReference<>();
    when(coordinator.execute(eq(CASE_REFERENCE), any(UUID.class), isNull(), any()))
        .thenAnswer(invocation -> {
          Supplier<CaseEventTransactionCoordinator.CaseEventWrite<?>> work = invocation.getArgument(3);
          write.set(work.get());
          return new CaseEventTransactionCoordinator.TransactionResult<>(7L, Optional.of(
              new CaseEventTransactionCoordinator.CreatedResult<>(existingCase(), null)
          ));
        });

    executor.execute(
        CASE_REFERENCE,
        UUID.randomUUID(),
        context -> SystemEventResult.withoutStateTransition("system-note", "System note")
    );

    var user = write.get().user().userDetails();
    assertThat(user.getUid()).isEqualTo("configured-id");
    assertThat(user.getGivenName()).isEqualTo("Case");
    assertThat(user.getFamilyName()).isEqualTo("System");
    verifyNoInteractions(idamService);
  }

  private static DecentralisedCaseDetails existingCase() {
    var caseDetails = new CaseDetails();
    caseDetails.setId("1");
    caseDetails.setReference(CASE_REFERENCE);
    caseDetails.setCaseTypeId("CaseType");
    caseDetails.setState("Open");
    var details = new DecentralisedCaseDetails();
    details.setCaseDetails(caseDetails);
    return details;
  }
}
