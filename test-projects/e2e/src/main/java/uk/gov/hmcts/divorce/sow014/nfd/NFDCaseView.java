package uk.gov.hmcts.divorce.sow014.nfd;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundles;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.divorce.bundling.CaseBundleRepository;
import uk.gov.hmcts.divorce.bundling.CaseFileBundle;
import org.springframework.context.annotation.Lazy;
import uk.gov.hmcts.divorce.caseworker.model.CaseNote;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;

@Component
public class NFDCaseView implements CaseView<CaseData, State> {

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Autowired
    private CaseBundleRepository caseBundleRepository;

    @Autowired
    @Lazy
    private CaseBundles caseBundles;

    @Override
    public CaseData getCase(CaseViewRequest<State> request, CaseData blobCase) {
        blobCase.setNotes(loadNotes(request.caseRef()));
        blobCase.setCaseBundles(caseBundleRepository.findByCase(request.caseRef()));
        // CDAM serves a document to a user only when a case field refers to it, so the SDK's
        // current bundle is put on the case each time it is loaded; nothing is copied.
        blobCase.setCaseFileBundle(caseBundles.view(CaseFileBundle.NAME, request.caseRef()).current()
            .flatMap(bundle -> bundle.stored().map(stored -> Document.builder()
                .url(stored.url())
                .binaryUrl(stored.binaryUrl())
                .filename(bundle.fileName())
                .build()))
            .orElse(null));
        blobCase.setHyphenatedCaseRef(CaseData.formatCaseRef(request.caseRef()));
        return blobCase;
    }

    private List<ListValue<CaseNote>> loadNotes(long caseRef) {
        var params = new MapSqlParameterSource().addValue("caseRef", caseRef);
        var rows = db.query(
            "select author, timestamp, note from case_notes where reference = :caseRef order by id desc",
            params,
            BeanPropertyRowMapper.newInstance(CaseNote.class)
        );
        return rows.stream().map(n -> new ListValue<>(null, n)).toList();
    }
}
