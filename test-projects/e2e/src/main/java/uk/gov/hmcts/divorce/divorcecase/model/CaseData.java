package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.External;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.CaseLink;
import uk.gov.hmcts.ccd.sdk.type.ComponentLauncher;
import uk.gov.hmcts.ccd.sdk.type.FieldType;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.divorce.caseworker.model.CaseNote;
import uk.gov.hmcts.divorce.divorcecase.model.access.CaseworkerAccess;
import uk.gov.hmcts.divorce.divorcecase.model.access.CaseworkerCaseLinkAccess;
import uk.gov.hmcts.divorce.divorcecase.model.access.CaseworkerAndSuperUserAccess;
import uk.gov.hmcts.divorce.divorcecase.model.access.CaseworkerWithCAAAccess;
import uk.gov.hmcts.divorce.divorcecase.model.access.DefaultAccess;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
public class CaseData {

    @CCD(
        label = "Application type",
        access = {DefaultAccess.class},
        typeOverride = FieldType.FixedRadioList,
        typeParameterOverride = "ApplicationType"
    )
    private ApplicationType applicationType;

    @CCD(access = {DefaultAccess.class})
    private String setInAboutToStart;

    @JsonUnwrapped(prefix = "applicant1")
    @Builder.Default
    @CCD(access = {DefaultAccess.class})
    private Applicant applicant1 = new Applicant();

    @JsonUnwrapped(prefix = "applicant2")
    @Builder.Default
    @CCD(access = {DefaultAccess.class})
    private Applicant applicant2 = new Applicant();

    @CCD(
        label = "Notes",
        typeOverride = FieldType.Collection,
        typeParameterOverride = "CaseNote",
        access = {CaseworkerAndSuperUserAccess.class}
    )
    @External
    private List<ListValue<CaseNote>> notes;

    @CCD(
        label = "Linked cases",
        typeOverride = FieldType.Collection,
        typeParameterOverride = "CaseLink",
        access = {CaseworkerCaseLinkAccess.class}
    )
    @Builder.Default
    private List<ListValue<CaseLink>> caseLinks = new ArrayList<>();

    @CCD(
        label = "Component launcher for linked cases",
        access = {CaseworkerCaseLinkAccess.class}
    )
    @JsonProperty("LinkedCasesComponentLauncher")
    private ComponentLauncher linkedCasesComponentLauncher;

    @CCD(
        label = "Add a case note",
        hint = "Enter note",
        typeOverride = FieldType.TextArea,
        access = {CaseworkerAndSuperUserAccess.class}
    )
    @External
    private String note;

    @CCD(
        label = "Due Date",
        access = {DefaultAccess.class, CaseworkerWithCAAAccess.class}
    )
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dueDate;

    @CCD(access = {CaseworkerAccess.class})
    private String hyphenatedCaseRef;

    @CCD(
        label = "Test document",
        typeOverride = FieldType.Document
    )
    private Document testDocument;

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
