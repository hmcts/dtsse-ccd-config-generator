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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.TaskOutboxService;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPermission;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

@Component
@Slf4j
public class ApiFirstTaskDelayedEvent implements CCDConfig<CaseData, State, UserRole> {

  public static final String EVENT_ID = "api-first-create-task-delayed";

  @Autowired
  private TaskOutboxService taskOutboxService;

  @Override
  public void configure(ConfigBuilder<CaseData, State, UserRole> configBuilder) {
    new PageBuilder(configBuilder
        .event(EVENT_ID)
        .forAllStates()
        .name("API-first delayed task")
        .description("Create API-first task with delayed WA initiation")
        .aboutToSubmitCallback(this::aboutToSubmit)
        .showEventNotes()
        .grant(CREATE_READ_UPDATE, CASE_WORKER, JUDGE)
        .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER)
        .grantHistoryOnly(LEGAL_ADVISOR, JUDGE))
        .page("apiFirstTaskDelayed")
        .pageLabel("API-first delayed task")
        .optional(CaseData::getNote);
  }

  public AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(
      CaseDetails<CaseData, State> details,
      CaseDetails<CaseData, State> beforeDetails
  ) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String taskId = UUID.randomUUID().toString();
    String caseId = String.valueOf(details.getId());

    TaskPayload task = TaskPayload.builder()
        .externalTaskId(taskId)
        .type(ApiFirstTaskEvent.TASK_TYPE)
        .name("Register new case")
        .title("Register new case")
        .created(now)
        .executionType("Case Management Task")
        .caseId(caseId)
        .caseTypeId(NoFaultDivorce.getCaseType())
        .caseCategory("DIVORCE")
        .caseName("API-first delayed task case")
        .jurisdiction(NoFaultDivorce.JURISDICTION)
        .region("1")
        .location("336559")
        .workType("applications")
        .roleCategory("ADMIN")
        .securityClassification("PUBLIC")
        .description("[Case: Edit case](/cases/case-details/" + caseId + "/trigger/edit-case)")
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

    taskOutboxService.enqueueTaskCreateRequest(new TaskCreateRequest(task), now.plusHours(1).toLocalDateTime());

    return AboutToStartOrSubmitResponse.<CaseData, State>builder()
        .data(details.getData())
        .build();
  }
}
