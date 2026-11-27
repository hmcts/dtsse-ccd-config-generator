package uk.gov.hmcts.divorce.jobs;

import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.jobs.CaseJob;
import uk.gov.hmcts.ccd.sdk.jobs.CaseJobOutcome;
import uk.gov.hmcts.ccd.sdk.jobs.CaseMetadata;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;

/**
 * A one-off job, run in an environment by setting CCD_JOBS_RUN_NOTE_EVERY_CASE to a run id: adds a case note to
 * every case not withdrawn. The SDK runs each case as a system event, so the note is audited like any change.
 */
@Component
@RequiredArgsConstructor
public class NoteEveryCase implements CaseJob {

    public static final String NOTE = "Reviewed by the note-every-case job";

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
    public CaseJobOutcome apply(CaseMetadata metadata) {
        if ("Withdrawn".equals(metadata.state())) {
            return CaseJobOutcome.skipped();
        }
        db.update("insert into case_notes(reference, author, note) values (:reference, 'System', :note)",
            Map.of("reference", metadata.reference(), "note", NOTE));
        return CaseJobOutcome.applied();
    }
}
