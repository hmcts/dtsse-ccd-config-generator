package uk.gov.hmcts.divorce.sow014.nfd;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CaseRepository;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.type.YesOrNo;
import uk.gov.hmcts.divorce.caseworker.model.CaseNote;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.sow014.Party;
import uk.gov.hmcts.divorce.divorcecase.model.sow014.Solicitor;
import uk.gov.hmcts.divorce.idam.IdamService;
import uk.gov.hmcts.divorce.idam.User;

import java.io.IOException;
import java.util.*;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

@Slf4j
@Component
public class NFDCaseRepository implements CaseRepository<CaseData> {

    @Autowired
    private NamedParameterJdbcTemplate db;

    @Autowired
    private ObjectMapper getMapper;

    @Autowired
    private IdamService idamService;

    @Autowired
    private HttpServletRequest request;

    @SneakyThrows
    @Override
    public CaseData getCase(long caseRef, String state, CaseData caseData) {
        var params = new MapSqlParameterSource().addValue("caseRef", caseRef);
        var isLeadCase = db.queryForList("select 1 from multiples where lead_case_id = :caseRef limit 1", params);
        if (!isLeadCase.isEmpty()) {
            addLeadCaseInfo(caseRef, caseData);
        } else {
            caseData = addSubCaseInfo(caseRef, caseData);
        }

        caseData.setNotes(loadNotes(caseRef));
        caseData.setParties(loadParties(caseRef));
        caseData.setSolicitors(loadSolicitors(caseRef));

        caseData.setHyphenatedCaseRef(CaseData.formatCaseRef(caseRef));

        addAdminPanel(caseRef, caseData);

        addPendingApplications(caseRef, caseData);
        addClaims(caseRef, caseData);
//        addSolicitorClaims(caseRef, caseData);

        return caseData;
    }

    private List<ListValue<Solicitor>> loadSolicitors(long caseRef) {
        var params = new MapSqlParameterSource().addValue("caseRef", caseRef);
        var rows = db.query(
            "select solicitor_id as \"solicitorId\", reference, role, forename, surname, version " +
                "from civil.solicitors where reference = :caseRef order by solicitor_id desc",
            params,
            BeanPropertyRowMapper.newInstance(Solicitor.class)
        );
        return rows.stream().map(n -> new ListValue<>(null, n)).toList();
    }

    private List<ListValue<Party>> loadParties(long caseRef) {
        var params = new MapSqlParameterSource().addValue("caseRef", caseRef);
        var rows = db.query(
            "select party_id as \"partyId\", version, forename, surname " +
                "from civil.parties where reference = :caseRef order by party_id desc",
            params,
            BeanPropertyRowMapper.newInstance(Party.class)
        );
        return rows.stream().map(n -> new ListValue<>(null, n)).toList();
    }

    @SneakyThrows
    private void addSolicitorClaims(long caseRef, CaseData caseData) {
        final User caseworkerUser = idamService.retrieveUser(request.getHeader(AUTHORIZATION));

        var params = new MapSqlParameterSource()
            .addValue("caseRef", caseRef)
            .addValue("solicitorId", Long.valueOf(caseworkerUser.getUserDetails().getUid()));

        var clients = db.queryForList(
            "select solicitor_id, forename, role, reference, description, amount_pence as \"amountPence\" " +
                "from civil.claims_by_client where reference = :caseRef and solicitor_id = :solicitorId",
            params
        );
    }

    @SneakyThrows
    private void addClaims(long caseRef, CaseData caseData) {
        var params = new MapSqlParameterSource().addValue("caseRef", caseRef);
        var claims = db.queryForList(
            "select claim_id, reference, description, amount_pence as \"amountPence\", " +
                "claimants::text as claimants, defendants::text as defendants " +
                "from civil.judge_claims where reference = :caseRef",
            params
        );

    }

    @SneakyThrows
    private void addPendingApplications(long caseRef, CaseData caseData) {
        var applications = db.queryForList(
            "select forename, description, reason from civil.pending_applications",
            new MapSqlParameterSource()
        );

    }

    private void addAdminPanel(long caseRef, CaseData caseData) throws IOException {
    }

    private void addLeadCaseInfo(long caseRef, CaseData caseData) throws IOException {
        var params = new MapSqlParameterSource().addValue("caseRef", caseRef);
        var total = db.queryForObject(
            "select count(*) from sub_cases where lead_case_id = :caseRef",
            params,
            Integer.class
        );
        var subCases = db.queryForList(
            "select name, " +
                "lead_case_id as \"leadCaseId\", " +
                "sub_case_id as \"subCaseId\", " +
                "last_modified as lastmodified, " +
                "applicant1FirstName as applicant1firstname, " +
                "applicant1LastName as applicant1lastname " +
                "from sub_cases where lead_case_id = :caseRef order by last_modified desc limit 50",
            params
        );
        if (!subCases.isEmpty()) {
            caseData.setLeadCase(YesOrNo.YES);

        } else {
            caseData.setLeadCase(YesOrNo.NO);
        }
    }

    private CaseData addSubCaseInfo(long caseRef, CaseData caseData) throws IOException {
        var params = new MapSqlParameterSource().addValue("caseRef", caseRef);
        var leadCases = db.queryForList(
            "select name, lead_case_id as \"leadCaseId\" from sub_cases where sub_case_id = :caseRef",
            params
        );

        if (!leadCases.isEmpty()) {
            var json = db.queryForObject(
                "select data::text as data from derived_cases where sub_case_id = :caseRef",
                params,
                String.class
            );
            caseData = getMapper.readValue(json, CaseData.class);
            caseData.setLeadCase(YesOrNo.NO);

        }
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
