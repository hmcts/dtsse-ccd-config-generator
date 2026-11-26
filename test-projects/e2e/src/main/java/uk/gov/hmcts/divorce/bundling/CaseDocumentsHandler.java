package uk.gov.hmcts.divorce.bundling;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJob;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobHandler;
import uk.gov.hmcts.ccd.sdk.doctoolkit.UploadedDoc;

/**
 * Receives every document the doc toolkit renders for this service, telling them apart by key: each version of a
 * case's notes document ({@link CaseNotesDocument}), and each hearing bundle a caseworker asked for
 * ({@link CaseworkerCreateBundle}).
 */
@Component
public class CaseDocumentsHandler implements DocJobHandler {

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Autowired
    private BundlePublisher bundlePublisher;

    @Override
    public void completed(final DocJob job, final UploadedDoc uploaded) {
        if (job.key().equals(CaseNotesDocument.KEY)) {
            db.update("insert into case_notes_documents (reference, case_revision, url, page_count)"
                    + " values (:reference, :revision, :url, :pages)",
                Map.of("reference", job.caseReference(), "revision", job.caseRevision(),
                    "url", uploaded.document().getUrl(), "pages", uploaded.pageCount()));
        } else if (job.key().startsWith(BundlePublisher.KEY_PREFIX)) {
            bundlePublisher.completed(job.caseReference(), job.key().substring(BundlePublisher.KEY_PREFIX.length()),
                uploaded);
        } else {
            throw new IllegalStateException("No document is submitted as " + job.key());
        }
    }
}
