package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.impl.CaseProjectionService;
import uk.gov.hmcts.ccd.sdk.impl.CaseSubmissionService;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.SystemEventRecordingService.ActorAttribution;

class SystemEventRecordingServiceTest {

  private static final long CASE_REF = 123456789L;

  private final CaseProjectionService caseProjectionService = mock(CaseProjectionService.class);
  private final ResolvedConfigRegistry resolvedConfigRegistry = mock(ResolvedConfigRegistry.class);
  private final CaseSubmissionService submissionService = mock(CaseSubmissionService.class);

  private final SystemEventRecordingService recorder = new SystemEventRecordingService(
      caseProjectionService, resolvedConfigRegistry, submissionService);

  private enum State { Open, Closed }

  @Test
  void recordsAnEventCarryingTheCurrentSnapshotAndActor() {
    DecentralisedCaseDetails current = currentCase("Open");
    when(caseProjectionService.load(CASE_REF)).thenReturn(current);
    Event<?, ?, ?> eventConfig = mock(Event.class);
    doReturn(Set.of(State.Open)).when(eventConfig).getPreState();
    when(eventConfig.getName()).thenReturn("Notice of change applied");
    doReturn(eventConfig).when(resolvedConfigRegistry).getRequiredEvent("TestCase", "systemTouch");
    recorder.recordSystemEvent(CASE_REF, "systemTouch", "token",
        "Notice of change by a@b.com", new ActorAttribution("uid-1", "Jane", "Doe"));

    ArgumentCaptor<DecentralisedCaseEvent> captor = forClass(DecentralisedCaseEvent.class);
    verify(submissionService).submit(captor.capture(), eq("token"), any(UUID.class));
    DecentralisedCaseEvent event = captor.getValue();
    assertThat(event.getCaseDetails()).isSameAs(current.getCaseDetails());
    assertThat(event.getCaseDetailsBefore()).isSameAs(current.getCaseDetails());
    assertThat(event.getInternalCaseId()).isEqualTo(42L);
    assertThat(event.getStartRevision()).isEqualTo(7L);
    assertThat(event.getEventDetails().getEventId()).isEqualTo("systemTouch");
    assertThat(event.getEventDetails().getEventName()).isEqualTo("Notice of change applied");
    assertThat(event.getEventDetails().getSummary()).isEqualTo("Notice of change by a@b.com");
    assertThat(event.getEventDetails().getProxiedBy()).isEqualTo("uid-1");
    assertThat(event.getEventDetails().getProxiedByFirstName()).isEqualTo("Jane");
    assertThat(event.getEventDetails().getProxiedByLastName()).isEqualTo("Doe");
  }

  @Test
  void rejectsAnEventNotPermittedInTheCurrentState() {
    when(caseProjectionService.load(CASE_REF)).thenReturn(currentCase("Closed"));
    Event<?, ?, ?> eventConfig = mock(Event.class);
    doReturn(Set.of(State.Open)).when(eventConfig).getPreState();
    doReturn(eventConfig).when(resolvedConfigRegistry).getRequiredEvent("TestCase", "systemTouch");

    assertThatThrownBy(() -> recorder.recordSystemEvent(CASE_REF, "systemTouch", "token", null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not permitted in state Closed");
  }

  @Test
  void actorIsOptional() {
    DecentralisedCaseDetails current = currentCase("Open");
    when(caseProjectionService.load(CASE_REF)).thenReturn(current);
    Event<?, ?, ?> eventConfig = mock(Event.class);
    doReturn(Set.of()).when(eventConfig).getPreState();
    doReturn(eventConfig).when(resolvedConfigRegistry).getRequiredEvent("TestCase", "systemTouch");

    recorder.recordSystemEvent(CASE_REF, "systemTouch", "token", null, null);

    ArgumentCaptor<DecentralisedCaseEvent> captor = forClass(DecentralisedCaseEvent.class);
    verify(submissionService).submit(captor.capture(), eq("token"), any(UUID.class));
    assertThat(captor.getValue().getEventDetails().getProxiedBy()).isNull();
    assertThat(captor.getValue().getEventDetails().getSummary()).isNull();
  }

  private DecentralisedCaseDetails currentCase(String state) {
    CaseDetails caseDetails = new CaseDetails();
    caseDetails.setId("42");
    caseDetails.setReference(CASE_REF);
    caseDetails.setCaseTypeId("TestCase");
    caseDetails.setState(state);
    DecentralisedCaseDetails details = new DecentralisedCaseDetails();
    details.setCaseDetails(caseDetails);
    details.setRevision(7L);
    return details;
  }
}
