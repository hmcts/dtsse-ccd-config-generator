package uk.gov.hmcts.divorce.sow014.nfd;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalEventId;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalRejection;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalStartRequest;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalStartResponse;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmitRequest;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmitResponse;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

/** External events, driven by a bespoke frontend exchanging typed payloads rather than case data. */
@Component
public class ExternalGreetingEvent implements CCDConfig<CaseData, State, UserRole> {

    /** The frontend is sent a greeting on start and posts a reply to it. */
    public static final ExternalEventId<Greeting, Reply> GREETING =
        ExternalEventId.of("greeting", Greeting.class, Reply.class);

    /** Nothing to show before saying goodbye, so this event has no start payload. */
    public static final ExternalEventId<Void, Farewell> FAREWELL = ExternalEventId.of("farewell", Farewell.class);

    public record Greeting(String text) {
    }

    public record Reply(String message) {
    }

    /** Whom the frontend asks the start to greet. */
    public record Addressee(String name) {
    }

    public record Farewell(String reason) {
    }

    /** What the greeting's after-commit work saw: whether it ran in a transaction, and the event's committed summary. */
    public record AfterCommitSighting(boolean inTransaction, String committedSummary) {
    }

    public volatile AfterCommitSighting lastAfterCommit;

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
            // Whether EXUI offers an external event is the service's choice; this one is hidden until withdrawal.
            .showCondition("[STATE]=\"Withdrawn\"")
            .grant(CREATE_READ_UPDATE, CASE_WORKER)
            .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER);
    }

    private ExternalStartResponse<Greeting> start(ExternalStartRequest start) {
        if (State.Withdrawn.name().equals(state(start.caseReference()))) {
            return ExternalStartResponse.rejected("The case has been withdrawn");
        }
        // The frontend can say whom to greet in its client context.
        String name = start.clientContext().as(Addressee.class).map(Addressee::name).orElse(start.user().id());
        return ExternalStartResponse.started(new Greeting("hello " + name));
    }

    private ExternalSubmitResponse<State> greet(ExternalSubmitRequest<Reply> submit) {
        keepAsNote(submit);
        submit.afterCommit(() -> lastAfterCommit = new AfterCommitSighting(
            TransactionSynchronizationManager.isActualTransactionActive(), latestGreetingSummary(submit.caseReference())));
        return ExternalSubmitResponse.accepted(submit.payload().message(), "Greeted by " + submit.user().id());
    }

    /** Writes the reply before checking it, so a rejection thrown here must roll the write back. */
    private void keepAsNote(ExternalSubmitRequest<Reply> submit) {
        String message = submit.payload().message();
        db.update("insert into case_notes(reference, author, note) values (:reference, :author, :note)",
            Map.of("reference", submit.caseReference(), "author", submit.user().id(),
                "note", message == null ? "" : message));
        if (message == null || message.isBlank()) {
            throw ExternalRejection.because("Say something");
        }
    }

    private ExternalSubmitResponse<State> farewell(ExternalSubmitRequest<Farewell> submit) {
        return ExternalSubmitResponse.<State>accepted(submit.payload().reason(), "Said goodbye")
            .movingTo(State.Withdrawn);
    }

    private String latestGreetingSummary(long caseReference) {
        return db.queryForObject("""
            select ce.summary from ccd.case_event ce join ccd.case_data cd on cd.id = ce.case_data_id
            where cd.reference = :reference and ce.event_id = :event order by ce.id desc limit 1
            """, Map.of("reference", caseReference, "event", GREETING.id()), String.class);
    }

    private String state(long caseReference) {
        return db.queryForObject("select state from ccd.case_data where reference = :reference",
            Map.of("reference", caseReference), String.class);
    }
}
