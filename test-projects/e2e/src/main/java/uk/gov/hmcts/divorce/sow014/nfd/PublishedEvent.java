package uk.gov.hmcts.divorce.sow014.nfd;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

@Component
@Slf4j
public class PublishedEvent implements CCDConfig<CaseData, State, UserRole> {
    public static final String ERROR_MESSAGE_OVERRIDE_NOTE = "error-message-override";
    public static final String ERROR_MESSAGE_OVERRIDE = "Published event rejected by error message override";

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Override
    public void configure(final ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        new PageBuilder(configBuilder
            .event(PublishedEvent.class.getSimpleName())
            .forAllStates()
            .name("Published Event")
            .description("Published Event")
            .publishToCamunda()
            .aboutToSubmitCallback(this::aboutToSubmit)
            .showEventNotes()
            .grant(CREATE_READ_UPDATE,
                CASE_WORKER, JUDGE)
            .grant(CREATE_READ_UPDATE_DELETE,
                SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE))
            .page("addCaseNotes")
            .pageLabel("Add case notes")
            .optional(CaseData::getNote)
            .readonly(CaseData::getNotes);
    }

    private AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(
        CaseDetails<CaseData, State> details,
        CaseDetails<CaseData, State> beforeDetails
    ) {
        var response = AboutToStartOrSubmitResponse.<CaseData, State>builder().data(details.getData());
        if (ERROR_MESSAGE_OVERRIDE_NOTE.equals(details.getData().getNote())) {
            db.update(
                "insert into case_notes(reference, author, note) values (:reference, 'E2E', :note)",
                Map.of("reference", details.getId(), "note", ERROR_MESSAGE_OVERRIDE_NOTE)
            );
            response.errorMessageOverride(ERROR_MESSAGE_OVERRIDE);
        }
        return response.build();
    }
}
