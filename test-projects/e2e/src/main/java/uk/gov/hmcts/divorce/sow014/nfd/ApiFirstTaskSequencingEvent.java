package uk.gov.hmcts.divorce.sow014.nfd;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.TaskManagementApiClient;
import uk.gov.hmcts.ccd.sdk.taskmanagement.TaskOutboxService;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPermission;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.ReconfigureTaskOutboxPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxTrigger;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TerminateTaskOutboxPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

@Component
public class ApiFirstTaskSequencingEvent implements CCDConfig<CaseData, State, UserRole> {

  public static final String EVENT_ID = "api-first-task-sequencing";
  private static final String TASK_TYPE = "sequencedTask";

  @Autowired
  private TaskOutboxService taskOutboxService;

  @Autowired
  private TaskManagementApiClient taskManagementApiClient;

  @Override
  public void configure(ConfigBuilder<CaseData, State, UserRole> configBuilder) {
    new PageBuilder(configBuilder
        .event(EVENT_ID)
        .forAllStates()
        .name("API-first task sequencing")
        .description("API-first task sequencing")
        .aboutToSubmitCallback(this::aboutToSubmit)
        .showEventNotes()
        .grant(CREATE_READ_UPDATE, CASE_WORKER, JUDGE)
        .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER)
        .grantHistoryOnly(LEGAL_ADVISOR, JUDGE))
        .page("apiFirstTaskSequencing")
        .pageLabel("API-first task sequencing")
        .optional(CaseData::getNote);
  }

  public AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(
      CaseDetails<CaseData, State> details,
      CaseDetails<CaseData, State> beforeDetails
  ) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String caseId = String.valueOf(details.getId());
    String caseType = NoFaultDivorce.getCaseType();
    TaskOutboxTrigger trigger = new TaskOutboxTrigger(caseId, caseType, EVENT_ID, now.toLocalDateTime());
    var getTasksResponse = taskManagementApiClient.getTasks(caseId, List.of(ApiFirstTaskEvent.TASK_TYPE));
    if (!getTasksResponse.getStatusCode().is2xxSuccessful()
        || getTasksResponse.getBody() == null
        || getTasksResponse.getBody().getTasks().isEmpty()) {
      throw new IllegalStateException("Failed to retrieve a task for the sequencing reconfiguration request");
    }

    TaskPayload task = TaskPayload.builder()
        .externalTaskId(UUID.randomUUID().toString())
        .type(TASK_TYPE)
        .name("Sequenced task")
        .title("Sequenced task")
        .created(now)
        .executionType("Case Management Task")
        .caseId(caseId)
        .caseTypeId(caseType)
        .caseCategory("DIVORCE")
        .caseName("API-first sequencing task case")
        .jurisdiction(NoFaultDivorce.JURISDICTION)
        .region("1")
        .location("336559")
        .workType("applications")
        .roleCategory("ADMIN")
        .securityClassification("PUBLIC")
        .description("[Case](/cases/case-details/" + caseId + ")")
        .dueDateTime(now.plusDays(5))
        .priorityDate(now.plusDays(5))
        .minorPriority(500)
        .locationName("Glasgow Tribunals Centre")
        .regionName("Scotland")
        .taskSystem("SELF")
        .additionalProperties(Map.of("originating_caseworker", "system"))
        .permissions(List.of(TaskPermission.builder()
            .roleName("caseworker-divorce")
            .roleCategory("ADMIN")
            .permissions(List.of("Read", "Own", "Claim", "Unclaim", "Manage", "Complete"))
            .authorisations(List.of("AUTH_1"))
            .assignmentPriority(1)
            .autoAssignable(false)
            .build()))
        .build();

    taskOutboxService.enqueueTaskCreateRequest(trigger, new TaskCreateRequest(task));
    taskOutboxService.enqueueTaskReconfigureRequest(
        trigger,
        new ReconfigureTaskOutboxPayload(caseId, caseType, getTasksResponse.getBody().getTasks())
    );
    taskOutboxService.enqueueTaskCancelRequest(
        trigger,
        new TerminateTaskOutboxPayload(caseId, caseType, List.of("taskTypeThatDoesNotExist"))
    );

    return AboutToStartOrSubmitResponse.<CaseData, State>builder()
        .data(details.getData())
        .build();
  }
}
