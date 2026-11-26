package uk.gov.hmcts.divorce.bundling;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CaseEventContext;
import uk.gov.hmcts.ccd.sdk.CaseEventHook;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobs;
import uk.gov.hmcts.ccd.sdk.doctoolkit.HtmlPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfRequest;

/**
 * The doc toolkit's worked example of a document kept up to date with its case: the case's notes as a PDF. After
 * every event on a case with notes it submits the notes; the toolkit renders them in the background when they
 * changed and hands each version to {@link CaseDocumentsHandler}.
 */
@Component
public class CaseNotesDocument implements CaseEventHook {

    public static final String KEY = "case-notes";

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Autowired
    private DocJobs docJobs;

    @Override
    public void afterCaseEvent(final CaseEventContext event) {
        List<Map<String, Object>> notes = db.queryForList(
            "select author, note from case_notes where reference = :reference order by id",
            Map.of("reference", event.caseReference()));
        if (notes.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder("<h1>Case notes</h1>");
        for (Map<String, Object> note : notes) {
            body.append("<p><strong>").append(escape(note.get("author"))).append("</strong>: ")
                .append(escape(note.get("note"))).append("</p>");
        }
        docJobs.submit(event.caseReference(), KEY, PdfRequest.builder("Case notes")
            .fileName("case-notes-" + event.caseReference() + ".pdf")
            .document(HtmlPage.builder().title("Case notes").body(body.toString()).build())
            .build());
    }

    private static String escape(final Object text) {
        return String.valueOf(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
