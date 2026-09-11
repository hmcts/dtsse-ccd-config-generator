package uk.gov.hmcts.divorce.sow014.nfd;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;

import lombok.RequiredArgsConstructor;
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

@Component
@RequiredArgsConstructor
public class NonConcurrentGroupEvents implements CCDConfig<CaseData, State, UserRole> {

    public static final String FIRST_EVENT = "caseworker-non-concurrent-group-first";
    public static final String SECOND_EVENT = "caseworker-non-concurrent-group-second";
    private static final String GROUP = "case-notes-exclusive";

    private final NamedParameterJdbcTemplate db;

    @Override
    public void configureDecentralised(DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        configureEvent(configBuilder, FIRST_EVENT, "nonConcurrentGroupFirst");
        configureEvent(configBuilder, SECOND_EVENT, "nonConcurrentGroupSecond");
    }

    private void configureEvent(
        DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder,
        String eventId,
        String pageId
    ) {
        EventBuilder<CaseData, UserRole, State> eventBuilder = configBuilder
            .decentralisedEvent(eventId, this::submit)
            .forAllStates()
            .name("Add exclusive case note")
            .nonConcurrentGroups(GROUP)
            .grant(CREATE_READ_UPDATE, CASE_WORKER, JUDGE);

        new PageBuilder(eventBuilder)
            .page(pageId)
            .pageLabel("Add exclusive case note")
            .optional(CaseData::getNote);
    }

    private SubmitResponse<State> submit(EventPayload<CaseData, State> payload) {
        var params = new MapSqlParameterSource()
            .addValue("reference", payload.caseReference())
            .addValue("author", "non-concurrent-group-test")
            .addValue("note", payload.caseData().getNote());

        db.update(
            "insert into case_notes(reference, author, note) values (:reference, :author, :note)",
            params
        );

        return SubmitResponse.defaultResponse();
    }
}
