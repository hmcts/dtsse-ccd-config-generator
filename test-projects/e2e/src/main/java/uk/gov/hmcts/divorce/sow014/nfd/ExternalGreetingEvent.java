package uk.gov.hmcts.divorce.sow014.nfd;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalEventId;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalStart;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalStartResponse;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmit;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmitResponse;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

/** External events, driven by a bespoke frontend exchanging typed payloads rather than case data. */
@Component
public class ExternalGreetingEvent implements CCDConfig<CaseData, State, UserRole> {

    /** The frontend is sent a greeting on start and posts a reply to it. */
    public static final ExternalEventId<Greeting, Reply> GREETING =
        ExternalEventId.of("ext:greeting", Greeting.class, Reply.class);

    /** Nothing to show before saying goodbye, so this event has no start payload. */
    public static final ExternalEventId<Void, Farewell> FAREWELL = ExternalEventId.of("ext:farewell", Farewell.class);

    public record Greeting(String text) {
    }

    public record Reply(String message) {
    }

    public record Farewell(String reason) {
    }

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Override
    public void configureDecentralised(final DecentralisedConfigBuilder<CaseData, State, UserRole> configBuilder) {
        configBuilder
            .externalEvent(GREETING, this::greet)
            .forAllStates()
            .name("Greeting")
            .grant(CREATE_READ_UPDATE, CASE_WORKER)
            .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER)
            .onStart(this::start);

        configBuilder
            .externalEvent(FAREWELL, this::farewell)
            .forAllStates()
            .name("Farewell")
            .grant(CREATE_READ_UPDATE, CASE_WORKER)
            .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER);
    }

    private ExternalStartResponse<Greeting> start(ExternalStart start) {
        if (State.Withdrawn.name().equals(state(start.caseReference()))) {
            return ExternalStartResponse.rejected("The case has been withdrawn");
        }
        return ExternalStartResponse.started(new Greeting("hello " + start.caseReference()));
    }

    private ExternalSubmitResponse<State> greet(ExternalSubmit<Reply> submit) {
        if (submit.payload().message().isBlank()) {
            return ExternalSubmitResponse.rejected("Say something");
        }
        return ExternalSubmitResponse.accepted(submit.payload().message(), "Greeted from an external frontend");
    }

    private ExternalSubmitResponse<State> farewell(ExternalSubmit<Farewell> submit) {
        return ExternalSubmitResponse.<State>accepted(submit.payload().reason(), "Said goodbye")
            .movingTo(State.Withdrawn);
    }

    private String state(long caseReference) {
        return db.queryForObject("select state from ccd.case_data where reference = :reference",
            Map.of("reference", caseReference), String.class);
    }
}
