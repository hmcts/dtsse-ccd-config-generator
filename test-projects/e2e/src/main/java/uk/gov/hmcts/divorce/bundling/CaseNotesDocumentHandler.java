package uk.gov.hmcts.divorce.bundling;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJob;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobHandler;
import uk.gov.hmcts.ccd.sdk.doctoolkit.UploadedDoc;

/** Keeps each version of a case's notes document the toolkit renders. */
@Component
public class CaseNotesDocumentHandler implements DocJobHandler {

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Override
    public void completed(final DocJob job, final UploadedDoc uploaded) {
        db.update("insert into case_notes_documents (reference, revision, url, page_count)"
                + " values (:reference, :revision, :url, :pages)",
            Map.of("reference", job.caseReference(), "revision", job.revision(),
                "url", uploaded.document().getUrl(), "pages", uploaded.pageCount()));
    }
}
