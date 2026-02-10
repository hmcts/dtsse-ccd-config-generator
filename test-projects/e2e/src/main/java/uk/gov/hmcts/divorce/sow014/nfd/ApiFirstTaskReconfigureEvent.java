package uk.gov.hmcts.divorce.sow014.nfd;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.TaskManagementApiClient;
import uk.gov.hmcts.ccd.sdk.taskmanagement.TaskOutboxService;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.ReconfigureTaskOutboxPayload;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

@Component
@Slf4j
public class ApiFirstTaskReconfigureEvent implements CCDConfig<CaseData, State, UserRole> {

  public static final String EVENT_ID = "api-first-reconfigure-task";

  @Autowired
  private TaskOutboxService taskOutboxService;

  @Autowired
  private TaskManagementApiClient taskManagementApiClient;

  @Override
  public void configure(ConfigBuilder<CaseData, State, UserRole> configBuilder) {
    new PageBuilder(configBuilder
        .event(EVENT_ID)
        .forAllStates()
        .name("API-first task: reconfigure")
        .description("API-first task: reconfigure")
        .aboutToSubmitCallback(this::aboutToSubmit)
        .showEventNotes()
        .grant(CREATE_READ_UPDATE, CASE_WORKER, JUDGE)
        .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER)
        .grantHistoryOnly(LEGAL_ADVISOR, JUDGE))
        .page("apiFirstTaskReconfigure")
        .pageLabel("API-first task: reconfigure")
        .optional(CaseData::getNote);
  }

  public AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(
      CaseDetails<CaseData, State> details,
      CaseDetails<CaseData, State> beforeDetails) {

    var taskTypes = NFDTaskType.getTaskTypesFromProcessCategoryIdentifiers(
        Arrays.stream(ProcessCategoryIdentifiers.values()).toList());

    log.warn("Reconfiguring tasks for case {}: {}", details.getId(), taskTypes);

    String caseId = String.valueOf(details.getId());
    List<String> taskTypeNames = taskTypes.stream().map(Enum::name).toList();
    var tasksToReconfigure = taskManagementApiClient.searchTasks(caseId, taskTypeNames);

    ReconfigureTaskOutboxPayload payload = new ReconfigureTaskOutboxPayload(
        caseId,
        NoFaultDivorce.getCaseType(),
        tasksToReconfigure
    );

    taskOutboxService.enqueueTaskReconfigureRequest(payload);

    return AboutToStartOrSubmitResponse.<CaseData, State>builder()
        .data(details.getData())
        .build();
  }
}

