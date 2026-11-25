package uk.gov.hmcts.divorce.bundling;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJob;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobHandler;
import uk.gov.hmcts.ccd.sdk.doctoolkit.UploadedDoc;

/** Keeps each version of a case's notes document the toolkit renders. */
@Slf4j
@Component
public class CaseNotesDocumentHandler implements DocJobHandler {

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Override
    public void completed(final DocJob job, final UploadedDoc uploaded) {
        // Called again with the same document if an earlier call failed; the url is unique.
        db.update("insert into case_notes_documents (reference, revision, url, page_count)"
                + " values (:reference, :revision, :url, :pages) on conflict (url) do nothing",
            Map.of("reference", job.caseReference(), "revision", job.revision().orElseThrow(),
                "url", uploaded.document().getUrl(), "pages", uploaded.pageCount()));
    }

    @Override
    public void failed(final DocJob job) {
        log.error("The {} document for case {} could not be made: {}", job.key().orElse("one-off"),
            job.caseReference(), job.failure().map(DocJob.Failure::message).orElse(""));
    }

    @Override
    public void removed(final DocJob job) {
        log.info("Case {} no longer has a {} document", job.caseReference(), job.key().orElse(""));
    }
}
