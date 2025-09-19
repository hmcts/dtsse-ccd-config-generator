package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.External;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.*;
import uk.gov.hmcts.divorce.caseworker.model.CaseNote;
import uk.gov.hmcts.divorce.divorcecase.model.access.*;
import uk.gov.hmcts.divorce.divorcecase.model.sow014.Party;
import uk.gov.hmcts.divorce.divorcecase.model.sow014.Solicitor;
import uk.gov.hmcts.divorce.sow014.lib.MyRadioList;

import java.time.LocalDate;
import java.util.List;
import static uk.gov.hmcts.ccd.sdk.type.FieldType.*;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
public class CaseData {

    @CCD(
        label = "Application type",
        access = {DefaultAccess.class},
        typeOverride = FixedRadioList,
        typeParameterOverride = "ApplicationType"
    )
    private ApplicationType applicationType;

    @CCD( access = {DefaultAccess.class})
    private String setInAboutToStart;


    @JsonUnwrapped(prefix = "applicant1")
    @Builder.Default
    @CCD(access = {DefaultAccess.class})
    private Applicant applicant1 = new Applicant();

    @JsonUnwrapped(prefix = "applicant2")
    @Builder.Default
    @CCD(access = {DefaultAccess.class})
    private Applicant applicant2 = new Applicant();


    private String interestedPartyForename;
    @External
    private String interestedPartyReason;

    @CCD(
        label = "Notes",
        typeOverride = Collection,
        typeParameterOverride = "CaseNote",
        access = {CaseworkerAndSuperUserAccess.class}
    )
    @External
    private List<ListValue<CaseNote>> notes;

    @CCD(
        label = "Add a case note",
        hint = "Enter note",
        typeOverride = TextArea,
        access = {CaseworkerAndSuperUserAccess.class}
    )
    @External
    private String note;

    @CCD(
        label = "Add case worker 1 case note",
        hint = "Enter note",
        typeOverride = TextArea,
        access = {CaseworkerAccess.class}
    )
    @External
    private String caseWorkerNote;

    @CCD(
        label = "Bulk list case reference",
        typeOverride = FieldType.CaseLink,
        access = {CaseworkerAccess.class}
    )
    private CaseLink bulkListCaseReferenceLink;

    @External
    private String markdownTabField;
    @External
    private YesOrNo leadCase;
    @External
    private String leadCaseMd;
    @External
    private String subCaseMd;
    @External
    private String pendingApplicationsMd;
    @External
    private String claimsMd;
    @External
    private String clientsMd;


    @External
    private String adminMd;

    @CCD(typeOverride = DynamicRadioList)
    @External
    private MyRadioList caseSearchResults;
    @CCD(label = "Search by applicant name")
    @External
    private String caseSearchTerm;

    @CCD(label = "Search by applicant name")
    @External
    private String callbackJobId;
    @CCD(typeOverride = DynamicRadioList)
    @External
    private MyRadioList callbackJobs;

    @CCD(label = "Document to scrub")
    private String documentToScrub;
    @CCD(typeOverride = DynamicRadioList, label = "Choose the document to scrub")
    @External
    private MyRadioList scrubbableDocs;


    @CCD(access = {CaseworkerAccess.class})
    private String hyphenatedCaseRef;





    @CCD(
        label = "Parties",
        typeOverride = Collection,
        typeParameterOverride = "Party",
        access = {SolicitorAccess.class, CaseworkerAccess.class}
    )
    @External
    private List<ListValue<Party>> parties;

    @CCD(
        label = "Solicitors",
        typeOverride = Collection,
        typeParameterOverride = "Solicitor",
        access = {SolicitorAccess.class, CaseworkerAccess.class}
    )
    @External
    private List<ListValue<Solicitor>> solicitors;

    @CCD(
        typeOverride = DynamicRadioList,
        label = "Select a party to update party details",
        access = {SolicitorAccess.class}
    )
    @External
    private MyRadioList partyNames;

    @CCD(
        label = "Add a party details",
        hint = "Enter a party's details",
        access = {SolicitorAccess.class}
    )
    @External
    private Party party;

    @CCD(
        label = "Due Date",
        access = {DefaultAccess.class, CaseworkerWithCAAAccess.class}
    )
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dueDate;

    @JsonIgnore
    public static String formatCaseRef(long caseId) {
        String temp = String.format("%016d", caseId);
        return String.format("%4s-%4s-%4s-%4s",
            temp.substring(0, 4),
            temp.substring(4, 8),
            temp.substring(8, 12),
            temp.substring(12, 16)
        );
    }

}
