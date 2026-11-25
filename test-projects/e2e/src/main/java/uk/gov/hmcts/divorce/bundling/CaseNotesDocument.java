package uk.gov.hmcts.divorce.bundling;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.doctoolkit.CaseDocDefinition;
import uk.gov.hmcts.ccd.sdk.doctoolkit.HtmlPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfRequest;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;

/**
 * The doc toolkit's worked example of a document kept up to date with its case: the case's notes as a PDF. After
 * every event on an E2E case the toolkit builds this request and, when the notes changed, renders it in the
 * background and hands it to {@link CaseNotesDocumentHandler}. A case with no notes has no document.
 */
@Component
public class CaseNotesDocument implements CaseDocDefinition {

    public static final String KEY = "case-notes";

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Set<String> caseTypes() {
        return Set.of(NoFaultDivorce.getCaseType());
    }

    @Override
    public Optional<PdfRequest> build(final long caseReference) {
        List<Map<String, Object>> notes = db.queryForList(
            "select author, note from case_notes where reference = :reference order by id",
            Map.of("reference", caseReference));
        if (notes.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder body = new StringBuilder("<h1>Case notes</h1>");
        for (Map<String, Object> note : notes) {
            body.append("<p><strong>").append(escape(note.get("author"))).append("</strong>: ")
                .append(escape(note.get("note"))).append("</p>");
        }
        return Optional.of(PdfRequest.builder("Case notes")
            .fileName("case-notes-" + caseReference + ".pdf")
            .document(HtmlPage.builder().title("Case notes").body(body.toString()).build())
            .build());
    }

    private static String escape(final Object text) {
        return String.valueOf(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
