package uk.gov.hmcts.divorce.sow014.nfd;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;
import uk.gov.hmcts.divorce.tasks.TaskOutboxRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class ApiFirstTaskEvent implements CCDConfig<CaseData, State, UserRole> {

    public static final String EVENT_ID = "api-first-create-task";

    @Autowired
    private TaskOutboxRepository taskOutboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void configure(ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        new PageBuilder(configBuilder
            .event(EVENT_ID)
            .forAllStates()
            .name("API-first task")
            .description("Create API-first task")
            .aboutToSubmitCallback(this::aboutToSubmit)
            .showEventNotes()
            .grant(CREATE_READ_UPDATE, CASE_WORKER, JUDGE)
            .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE))
            .page("apiFirstTask")
            .pageLabel("API-first task")
            .optional(CaseData::getNote);
    }

    public AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(
        CaseDetails<CaseData, State> details,
        CaseDetails<CaseData, State> beforeDetails
    ) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String taskId = UUID.randomUUID().toString();
        String caseId = String.valueOf(details.getId());

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("task_id", taskId);
        task.put("type", "registerNewCase");
        task.put("name", "Register new case");
        task.put("title", "Register new case");
        task.put("state", "UNASSIGNED");
        task.put("created", now);
        task.put("execution_type", "Case Management Task");
        task.put("case_id", caseId);
        task.put("case_type_id", NoFaultDivorce.getCaseType());
        task.put("case_category", "DIVORCE");
        task.put("case_name", "API-first task case");
        task.put("jurisdiction", NoFaultDivorce.JURISDICTION);
        task.put("region", "1");
        task.put("location", "336559");
        task.put("work_type", "applications");
        task.put("role_category", "ADMIN");
        task.put("security_classification", "PUBLIC");
        task.put("description", "[Case: Edit case](/cases/case-details/" + caseId + "/trigger/edit-case)");
        task.put("due_date_time", now.plusDays(5));
        task.put("priority_date", now.plusDays(5));
        task.put("major_priority", 5000);
        task.put("minor_priority", 500);
        task.put("location_name", "Glasgow Tribunals Centre");
        task.put("region_name", "Scotland");
        task.put("task_system", "SELF");
        task.put("additional_properties", Map.of(
            "originating_caseworker", "system"
        ));
        task.put("permissions", List.of(
            Map.of(
                "role_name", "caseworker-divorce",
                "role_category", "ADMIN",
                "permissions", List.of("Read", "Own", "Claim", "Unclaim", "Manage", "Complete"),
                "authorisations", List.of("AUTH_1"),
                "assignment_priority", 1,
                "auto_assignable", false
            )
        ));

        Map<String, Object> payload = Map.of("task", task);

        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            taskOutboxRepository.enqueue(taskId, caseId, NoFaultDivorce.getCaseType(), payloadJson);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to enqueue task outbox entry", ex);
        }

        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(details.getData())
            .build();
    }
}
