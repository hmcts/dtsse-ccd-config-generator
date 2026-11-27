package uk.gov.hmcts.divorce.jobs;

import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;
import uk.gov.hmcts.ccd.sdk.SystemEventResult;
import uk.gov.hmcts.ccd.sdk.jobs.CaseJob;
import uk.gov.hmcts.ccd.sdk.jobs.CaseJobOutcome;
import uk.gov.hmcts.ccd.sdk.jobs.CaseMetadata;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;

/**
 * A one-off job, run in an environment by setting CCD_JOBS_RUN_NOTE_EVERY_CASE to a run id: adds a case note to
 * every case, each in a system event of its own so the change is audited and in the case's history.
 */
@Component
@RequiredArgsConstructor
public class NoteEveryCase implements CaseJob {

    public static final String NOTE = "Reviewed by the note-every-case job";
    public static final String EVENT_ID = "note-every-case";

    private final ObjectProvider<SystemEventExecutor> systemEvents;
    private final NamedParameterJdbcTemplate db;

    @Override
    public String name() {
        return "note-every-case";
    }

    @Override
    public Set<String> caseTypes() {
        return Set.of(NoFaultDivorce.getCaseType());
    }

    @Override
    public ApplyIn applyIn() {
        return ApplyIn.NO_TRANSACTION;
    }

    @Override
    public CaseJobOutcome apply(CaseMetadata metadata) {
        systemEvents.getObject().execute(metadata.reference(), metadata.idempotencyKey(), context -> {
            db.update("insert into case_notes(reference, author, note) values (:reference, 'System', :note)",
                Map.of("reference", metadata.reference(), "note", NOTE));
            return SystemEventResult.withoutStateTransition(EVENT_ID, "Note every case");
        });
        return CaseJobOutcome.applied();
    }
}
