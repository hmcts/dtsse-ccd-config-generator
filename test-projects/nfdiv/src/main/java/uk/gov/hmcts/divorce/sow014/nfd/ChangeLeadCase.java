package uk.gov.hmcts.divorce.caseworker.event.page;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;
import uk.gov.hmcts.divorce.sow014.lib.DynamicRadioListElement;
import uk.gov.hmcts.divorce.sow014.lib.MyRadioList;

import java.util.ArrayList;
import java.util.List;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.*;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

@Component
@Slf4j
public class ChangeLeadCase implements CCDConfig<CaseData, State, UserRole> {
    @Autowired
    private NamedParameterJdbcTemplate db;

    @Override
    public void configure(final ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        new PageBuilder(configBuilder
            .event("change-lead-case")
            .forAllStates()
            .name("Change lead case")
            .description("Change lead case")
            .aboutToSubmitCallback(this::aboutToSubmit)
            .showCondition("leadCase=\"Yes\"")
            .showEventNotes()
            .grant(CREATE_READ_UPDATE,
                CASE_WORKER)
            .grant(CREATE_READ_UPDATE_DELETE,
                SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE))
            .page("changeLeadCase", this::searchCases)
            .pageLabel("Change the lead case")
            .mandatory(CaseData::getCaseSearchTerm)
            .page("Choose the new lead case")
            .mandatory(CaseData::getCaseSearchResults);
    }

    private AboutToStartOrSubmitResponse<CaseData, State> searchCases(CaseDetails<CaseData, State> details, CaseDetails<CaseData, State> beforeDetails) {
        var choices = new ArrayList<DynamicRadioListElement>();
        var params = new MapSqlParameterSource()
            .addValue("leadCaseId", details.getId())
            .addValue("term", details.getData().getCaseSearchTerm());

        var rows = db.queryForList(
            "select sub_case_id as \"subCaseId\", " +
                "applicant1FirstName as applicant1firstname, " +
                "applicant1LastName as applicant1lastname " +
                "from sub_cases where lead_case_id = :leadCaseId " +
                "and upper(applicant1FirstName) = upper(:term) limit 100",
            params
        );

        rows.forEach(r -> choices.add(DynamicRadioListElement.builder()
            .code(String.valueOf(r.get("subCaseId")))
            .label(r.get("subCaseId") + " - " + r.get("applicant1firstname") + " - " + r.get("applicant1lastname"))
            .build()));

        MyRadioList radioList = MyRadioList.builder()
            .value(choices.get(0))
            .listItems(choices)
            .build();

        details.getData().setCaseSearchResults(radioList);
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(details.getData())
            .build();
    }

    public AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(
        final CaseDetails<CaseData, State> details,
        final CaseDetails<CaseData, State> beforeDetails
    ) {

        var choice = details.getData().getCaseSearchResults().getValue();

        // Make the chosen case the lead case.
        var params = new MapSqlParameterSource()
            .addValue("newLead", Long.parseLong(choice.getCode()))
            .addValue("currentLead", details.getId());
        db.update(
            "update multiples set lead_case_id = :newLead where lead_case_id = :currentLead",
            params
        );

        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(details.getData())
            .build();
    }
}
