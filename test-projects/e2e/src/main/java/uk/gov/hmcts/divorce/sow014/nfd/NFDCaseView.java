package uk.gov.hmcts.divorce.sow014.nfd;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.LegacyJSONBlobCaseView;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.divorce.caseworker.model.CaseNote;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;

@Component
public class NFDCaseView extends LegacyJSONBlobCaseView<CaseData> {

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Override
    protected CaseData projectCase(long caseRef, String state, CaseData caseData) {
        caseData.setNotes(loadNotes(caseRef));
        caseData.setHyphenatedCaseRef(CaseData.formatCaseRef(caseRef));
        return caseData;
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
