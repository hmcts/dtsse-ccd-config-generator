package uk.gov.hmcts.divorce.sow014.nfd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.TaskOutboxService;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TerminateTaskOutboxPayload;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

import java.util.Arrays;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

@Component
@Slf4j
public class ApiFirstTaskCancelEvent implements CCDConfig<CaseData, State, UserRole> {

  public static final String EVENT_ID = "api-first-cancel-task";

  @Autowired
  private TaskOutboxService taskOutboxService;

  @Override
  public void configure(ConfigBuilder<CaseData, State, UserRole> configBuilder) {
    new PageBuilder(configBuilder
            .event(EVENT_ID)
            .forAllStates()
            .name("API-first task: cancel")
            .description("API-first task: cancel")
            .aboutToSubmitCallback(this::aboutToSubmit)
            .showEventNotes()
            .grant(CREATE_READ_UPDATE, CASE_WORKER, JUDGE)
            .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE))
            .page("apiFirstTaskCancel")
            .pageLabel("API-first task: cancel")
            .optional(CaseData::getNote);
  }

  public AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(
    CaseDetails<CaseData, State> details,
    CaseDetails<CaseData, State> beforeDetails) {

    //cancel all tasks - could also just enumerate all task types, but this demos rel between
    //process category identifiers and a task subset and a way to map them to each other
    var processCategoryIdentifiers = Arrays.stream(ProcessCategoryIdentifiers.values()).map(Enum::name).toList();
    var taskTypes = NFDTaskType.getTaskTypesFromProcessCategoryIdentifiers(processCategoryIdentifiers);

    TerminateTaskOutboxPayload payload = new TerminateTaskOutboxPayload(
      String.valueOf(details.getId()),
      NoFaultDivorce.getCaseType(),
      taskTypes.stream().map(Enum::name).toList()
    );

    taskOutboxService.enqueueTaskCancelRequest(payload);

    return AboutToStartOrSubmitResponse.<CaseData, State>builder()
      .data(details.getData())
      .build();
  }
}
