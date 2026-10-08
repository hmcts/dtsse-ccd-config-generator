package uk.gov.hmcts.divorce.simplecase.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.CaseQueriesCollection;
import uk.gov.hmcts.ccd.sdk.type.FieldType;
import uk.gov.hmcts.ccd.sdk.type.JudicialUser;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.type.TTL;
import uk.gov.hmcts.divorce.divorcecase.model.access.CaseworkerAccess;
import uk.gov.hmcts.divorce.divorcecase.model.access.DefaultAccess;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleCaseData {

    @CCD(label = "Subject", access = {CaseworkerAccess.class})
    private String subject;

    @CCD(label = "Description", access = {CaseworkerAccess.class})
    private String description;

    @CCD(label = "Creation callback marker", access = {CaseworkerAccess.class})
    private String creationMarker;

    @CCD(label = "Follow up note", access = {CaseworkerAccess.class})
    private String followUpNote;

    @CCD(label = "Follow up callback marker", access = {CaseworkerAccess.class})
    private String followUpMarker;

    @CCD(label = "Priority", access = {CaseworkerAccess.class})
    private SimpleCasePriority priority;

    @CCD(label = "Review date", access = {CaseworkerAccess.class})
    private LocalDate reviewDate;

    @CCD(label = "Allocated judge", typeOverride = FieldType.JudicialUser, access = {CaseworkerAccess.class})
    private JudicialUser allocatedJudge;

    @CCD(label = "Queries", access = {CaseworkerAccess.class})
    private CaseQueriesCollection queries;

    @CCD(access = {DefaultAccess.class})
    @Builder.Default
    private List<ListValue<SimpleCaseNote>> notes = new ArrayList<>();

    @CCD(access = {DefaultAccess.class})
    @Builder.Default
    private List<ListValue<SimpleCaseLink>> caseLinks = new ArrayList<>();

    @CCD(access = {CaseworkerAccess.class})
    private String hyphenatedCaseRef;

    @JsonProperty("TTL")
    @CCD(typeOverride = FieldType.TTL, access = {DefaultAccess.class})
    private TTL timeToLive;

}
