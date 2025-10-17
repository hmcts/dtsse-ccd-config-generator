package uk.gov.hmcts.divorce.sow014.nfd;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event.EventBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;
import uk.gov.hmcts.divorce.idam.IdamService;
import uk.gov.hmcts.divorce.idam.User;

@Component
@Slf4j
public class DecentralisedCaseworkerAddNoteFailure implements CCDConfig<CaseData, State, UserRole> {

    public static final String CASEWORKER_DECENTRALISED_ADD_NOTE_FAIL = "caseworker-decentralised-add-note-fail";

    @Autowired
    private HttpServletRequest request;

    @Autowired
    private IdamService idamService;

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Override
    public void configureDecentralised(final DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        EventBuilder<CaseData, UserRole, State> failingEvent = configBuilder
            .decentralisedEvent(CASEWORKER_DECENTRALISED_ADD_NOTE_FAIL, this::submitAndFail, this::start)
            .forAllStates()
            .name("Decentralised add note fail")
            .showEventNotes()
            .grant(CREATE_READ_UPDATE, CASE_WORKER, JUDGE)
            .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE);

        new PageBuilder(failingEvent)
            .page("addCaseNotesDecentralisedFail")
            .pageLabel("Add case notes (fail)")
            .optional(CaseData::getNote);
    }

    private CaseData start(EventPayload<CaseData, State> payload) {
        final User user = idamService.retrieveUser(request.getHeader(AUTHORIZATION));
        var caseData = payload.caseData();
        caseData.setNote("[start] set by " + user.getUserDetails().getName());
        return caseData;
    }

    private SubmitResponse submitAndFail(EventPayload<CaseData, State> payload) {
        final Long reference = payload.caseReference();
        final CaseData caseData = payload.caseData();
        final User caseworkerUser = idamService.retrieveUser(request.getHeader(AUTHORIZATION));

        var params = new MapSqlParameterSource()
            .addValue("reference", reference)
            .addValue("author", caseworkerUser.getUserDetails().getName())
            .addValue("note", caseData.getNote());

        db.update(
            "insert into case_notes(reference, author, note) values (:reference, :author, :note)",
            params
        );

        log.info("Decentralised add note (failure) submitted for case {}", reference);
        return SubmitResponse.builder()
            .errors(List.of("Simulated decentralised failure"))
            .build();
    }
}
