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
import uk.gov.hmcts.divorce.caseworker.model.CaseNote;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;

@Component
public class NFDCaseView implements CaseView<CaseData, State> {

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Override
    public CaseData getCase(CaseViewRequest<State> request, CaseData blobCase) {
        blobCase.setNotes(loadNotes(request.caseRef()));
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
