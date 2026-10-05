package uk.gov.hmcts.divorce.sow014.nfd;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.divorce.caseworker.model.CaseNote;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Component
public class SubmittedConfirmationCallback implements CCDConfig<CaseData, State, UserRole> {

    public static final String EVENT_ID = "submitted-confirmation";
    public static final String CONFIRMATION_HEADER = "# Case Updated";
    public static final String CONFIRMATION_BODY = "Case callback response propagated";
    public static final String SUBMIT_NOTE = "Note written during submitted-confirmation submit";

    // Note text seen by the most recent submitted callback in the current and before cases.
    public static volatile List<String> submittedNotes;
    public static volatile List<String> submittedBeforeNotes;

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Override
    public void configure(ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        new PageBuilder(configBuilder
            .event(EVENT_ID)
            .forAllStates()
            .name("Submitted confirmation")
            .description("Returns confirmation message")
            .aboutToSubmitCallback(this::aboutToSubmit)
            .submittedCallback(this::submitted)
            .grant(CREATE_READ_UPDATE, CASE_WORKER, JUDGE)
            .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE))
            .page("submittedConfirmation")
            .optional(CaseData::getNote);
    }

    private AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(CaseDetails<CaseData, State> details,
                                                                        CaseDetails<CaseData, State> beforeDetails) {
        // Service-owned write that only reaches the case through the CaseView.
        db.update(
            "insert into case_notes(reference, author, note) values (:reference, 'system', :note)",
            new MapSqlParameterSource()
                .addValue("reference", details.getId())
                .addValue("note", SUBMIT_NOTE)
        );
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(details.getData())
            .build();
    }

    private SubmittedCallbackResponse submitted(CaseDetails<CaseData, State> caseDetails,
                                                CaseDetails<CaseData, State> beforeDetails) {
        submittedNotes = noteText(caseDetails);
        submittedBeforeNotes = noteText(beforeDetails);
        return SubmittedCallbackResponse.builder()
            .confirmationHeader(CONFIRMATION_HEADER)
            .confirmationBody(CONFIRMATION_BODY)
            .build();
    }

    private static List<String> noteText(CaseDetails<CaseData, State> details) {
        return Optional.ofNullable(details.getData().getNotes()).orElse(List.of()).stream()
            .map(ListValue::getValue)
            .map(CaseNote::getNote)
            .toList();
    }
}
