package uk.gov.hmcts.divorce.sow014.nfd;

import lombok.Getter;

import java.util.List;
import java.util.stream.Stream;

@Getter
public enum NFDTaskType {
  processCaseWithdrawalDirections("Process case withdrawal directions", "Processing"),
  processCaseWithdrawalDirectionsListed("Process case withdrawal directions listed", "Processing"),
  processRule27Decision("Process Rule 27 decision", "Processing"),
  processRule27DecisionListed("Process Rule 27 decision listed", "Processing"),
  processListingDirections("Process listing directions", "Processing"),
  processListingDirectionsListed("Process listing directions listed", "Processing"),
  processDirectionsReListedCase("Process directions re. listed case", "Hearing"),
  processDirectionsReListedCaseWithin5Days("Process directions re. listed case (within 5 days)", "Hearing"),
  processSetAsideDirections("Process set aside directions", "Decision"),
  processCorrections("Process corrections", "Amendment"),
  processDirectionsReturned("Process directions returned", "Processing"),
  processPostponementDirections("Process postponement directions", "Hearing"),
  processTimeExtensionDirectionsReturned("Process time extension directions returned", "Processing"),
  processReinstatementDecisionNotice("Process reinstatement decision notice", "Application"),
  processOtherDirectionsReturned("Process other directions returned", "Processing"),
  processWrittenReasons("Process written reasons", "Decision"),
  processStrikeOutDirectionsReturned("Process strike out directions returned", "Processing"),
  processStayDirections("Process stay directions", "Processing"),
  processStayDirectionsListed("Process stay directions listed", "Processing"),
  issueDecisionNotice("Issue decision notice", ""),
  completeHearingOutcome("Complete hearing outcome", "HearingCompletion"),
  issueCaseToRespondent("Issue case to respondent", "IssueCase"),
  vetNewCaseDocuments("Vet new case documents", ""),
  reviewNewCaseAndProvideDirectionsLO("Review new case and provide directions - Legal Officer", "Processing"),
  reviewTimeExtensionRequestLO("Review time extension request - Legal Officer", "Processing"),
  reviewStrikeOutRequestLO("Review strike out request - Legal Officer", "Processing"),
  reviewStayRequestLO("Review stay request - Legal Officer", "Processing"),
  reviewStayRequestCaseListedLO("Review stay request case listed - Legal Officer", "Processing"),
  reviewListingDirectionsLO("Review listing directions - Legal Officer", "Processing"),
  reviewListingDirectionsCaseListedLO("Review listing directions case listed - Legal Officer", "Processing"),
  reviewWithdrawalRequestLO("Review withdrawal request - Legal Officer", "Processing"),
  reviewWithdrawalRequestCaseListedLO("Review withdrawal request case listed - Legal Officer", "Processing"),
  reviewRule27RequestLO("Review Rule 27 request - Legal Officer", "Processing"),
  reviewRule27RequestCaseListedLO("Review Rule 27 request case listed - Legal Officer", "Processing"),
  reviewListCaseLO("Review list case - Legal Officer", "Hearing"),
  reviewOtherRequestLO("Review reinstatement request - Legal Officer", "Processing"),
  reviewListCaseWithin5DaysLO("Review list case (within 5 days) - Legal Officer", "Hearing"),
  reviewPostponementRequestLO("Review postponement request - Legal Officer", "Hearing"),
  reviewReinstatementRequestLO("Review reinstatement request - Legal Officer", "Application"),
  reviewListCaseWithin5DaysJudge("Review list case (within 5 days) - Judge", "Hearing"),
  reviewPostponementRequestJudge("Review postponement request - Judge", "Hearing"),
  reviewCorrectionsRequest("Review corrections request", "Amendment"),
  reviewWrittenReasonsRequest("Review written reasons request", "Decision"),
  reviewReinstatementRequestJudge("Review reinstatement request - Judge", "Application"),
  reviewSetAsideRequest("Review set aside request", "Decision"),
  reviewStayRequestJudge("Review stay request - Judge", "Processing"),
  reviewStayRequestCaseListedJudge("Review stay request case listed - Judge", "Processing"),
  reviewNewCaseAndProvideDirectionsJudge("Review new case and provide directions - Judge", "Processing"),
  reviewOtherRequestJudge("Review other request - Judge", "Processing"),
  reviewWithdrawalRequestJudge("Review withdrawal request - Judge", "Processing"),
  reviewWithdrawalRequestCaseListedJudge("Review withdrawal request case listed - Judge", "Processing"),
  reviewRule27RequestJudge("Review Rule 27 request - Judge", "Processing"),
  reviewRule27RequestCaseListedJudge("Review Rule 27 request case listed - Judge", "Processing"),
  reviewListingDirectionsJudge("Review listing directions - Judge", "Processing"),
  reviewListingDirectionsCaseListedJudge("Review listing directions case listed - Judge", "Processing"),
  reviewListCaseJudge("Review list case - Judge", "Hearing"),
  reviewStrikeOutRequestJudge("Review strike out request - Judge", "Processing"),
  reviewTimeExtensionRequestJudge("Review time extension request - Judge", "Processing"),
  followUpNoncomplianceOfDirections("Follow up noncompliance of directions", "Processing"),
  registerNewCase("Register new case", ""),
  processFurtherEvidence("Process further evidence", "Processing"),
  stitchCollateHearingBundle("Stitch/collate hearing bundle", "HearingBundle"),
  reviewSpecificAccessRequestJudiciary("Review Specific Access Request Judiciary", ""),
  reviewSpecificAccessRequestLegalOps("Review Specific Access Request Legal Ops", ""),
  reviewSpecificAccessRequestAdmin("Review Specific Access Request Admin", ""),
  reviewSpecificAccessRequestCTSC("Review Specific Access Request CTSC", ""),
  createDueDate("Create due date", "IssueCase"),
  issueDueDate("Issue due date", "IssueCase"),
  reviewOrder("Review Order", "Decision");

  private final String taskName;
  private final String processCategoryIdentifier;

  NFDTaskType(String taskName, String processCategoryIdentifier) {
    this.taskName = taskName;
    this.processCategoryIdentifier = processCategoryIdentifier;
  }

  public static List<NFDTaskType> getTaskTypesFromProcessCategoryIdentifiers(List<String> processCategoryIdentifiers) {
    return Stream.of(NFDTaskType.values())
      .filter(taskType -> processCategoryIdentifiers.contains(taskType.getProcessCategoryIdentifier()))
      .toList();
  }
}
