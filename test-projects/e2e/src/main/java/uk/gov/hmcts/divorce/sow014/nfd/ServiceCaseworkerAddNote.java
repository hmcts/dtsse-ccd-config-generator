package uk.gov.hmcts.divorce.sow014.nfd;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import jakarta.servlet.http.HttpServletRequest;
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
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;
import uk.gov.hmcts.divorce.idam.IdamService;
import uk.gov.hmcts.divorce.idam.User;
import uk.gov.hmcts.divorce.sow014.nfd.model.CaseworkerAddNoteDto;

@Component
@Slf4j
public class ServiceCaseworkerAddNote implements CCDConfig<CaseData, State, UserRole> {

    public static final String CASEWORKER_SERVICE_ADD_NOTE = "caseworkerServiceAddNote";

    @Autowired
    private HttpServletRequest request;

    @Autowired
    private IdamService idamService;

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Override
    public void configureDecentralised(final DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        EventBuilder<CaseworkerAddNoteDto, UserRole, State> eventBuilder = configBuilder
            .serviceEvent(
                CASEWORKER_SERVICE_ADD_NOTE,
                CaseworkerAddNoteDto.class,
                this::submit,
                this::start
            )
            .forAllStates()
            .name("Add note (service event)")
            .showEventNotes()
            .grant(CREATE_READ_UPDATE, CASE_WORKER, JUDGE)
            .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER)
            .grantHistoryOnly(LEGAL_ADVISOR, JUDGE);

        eventBuilder.fields()
            .page("addCaseNotesServiceEvent")
            .pageLabel("Add case notes (service event)")
            .optional(CaseworkerAddNoteDto::getNote);
    }

    private CaseworkerAddNoteDto start(EventPayload<CaseworkerAddNoteDto, State> payload) {
        final User user = idamService.retrieveUser(request.getHeader(AUTHORIZATION));
        var caseData = payload.caseData();
        if (caseData == null) {
            caseData = new CaseworkerAddNoteDto();
        }
        caseData.setNote("[start] set by " + user.getUserDetails().getName());
        return caseData;
    }

    private SubmitResponse<State> submit(EventPayload<CaseworkerAddNoteDto, State> payload) {
        final Long reference = payload.caseReference();
        final User caseworkerUser = idamService.retrieveUser(request.getHeader(AUTHORIZATION));

        var params = new MapSqlParameterSource()
            .addValue("reference", reference)
            .addValue("author", caseworkerUser.getUserDetails().getName())
            .addValue("note", payload.caseData().getNote());

        db.update(
            "insert into case_notes(reference, author, note) values (:reference, :author, :note)",
            params
        );

        log.info("Service event add note submitted for case {}", reference);
        return SubmitResponse.<State>builder()
            .confirmationHeader("Service event submission complete")
            .confirmationBody("Case note saved successfully.")
            .build();
    }
}
