package uk.gov.hmcts.divorce.sow014.nfd;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.divorce.common.ccd.PageBuilder;
import uk.gov.hmcts.divorce.divorcecase.model.Applicant;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.model.State;
import uk.gov.hmcts.divorce.divorcecase.model.UserRole;

import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.CASE_WORKER;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.JUDGE;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.LEGAL_ADVISOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SOLICITOR;
import static uk.gov.hmcts.divorce.divorcecase.model.UserRole.SUPER_USER;
import static uk.gov.hmcts.divorce.divorcecase.model.access.Permissions.CREATE_READ_UPDATE_DELETE;

@Component
public class CaseworkerRoundTripData implements CCDConfig<CaseData, State, UserRole> {

    public static final String CASEWORKER_ROUNDTRIP_DATA = "caseworker-roundtrip-data";
    public static final String START_CALLBACK_MARKER = "set-in-about-to-start";
    public static final String MID_EVENT_MARKER = "set-in-mid-event";
    public static final String SUBMIT_CALLBACK_MARKER = "set-in-about-to-submit";

    @Override
    public void configure(final ConfigBuilder<CaseData, State, UserRole> configBuilder) {
        new PageBuilder(configBuilder
            .event(CASEWORKER_ROUNDTRIP_DATA)
            .forAllStates()
            .aboutToStartCallback(this::roundTripStart)
            .aboutToSubmitCallback(this::roundTripSubmit)
            .name("Populate round-trip data")
            .description("Populate high-signal fields for end-to-end verification")
            .showEventNotes()
            .grant(CREATE_READ_UPDATE_DELETE, SUPER_USER, CASE_WORKER)
            .grantHistoryOnly(SOLICITOR, LEGAL_ADVISOR, JUDGE))
            .page("roundTripData", this::roundTripMidEvent)
            .pageLabel("Round-trip data set")
            .optional(CaseData::getApplicationType)
            .optional(CaseData::getDueDate)
            .optional(CaseData::getTestDocument)
            .optional(CaseData::getSetInAboutToStart)
            .optional(CaseData::getSetInMidEvent)
            .optional(CaseData::getSetInAboutToSubmit)
            .complex(CaseData::getApplicant1)
                .optional(Applicant::getFirstName)
                .optional(Applicant::getLastName)
                .optional(Applicant::getMiddleName)
                .optional(Applicant::getEmail)
                .optional(Applicant::getPhoneNumber)
                .optional(Applicant::getLanguagePreferenceWelsh)
                .optional(Applicant::getAgreedToReceiveEmails)
            .done()
            .complex(CaseData::getApplicant2)
                .optional(Applicant::getFirstName)
                .optional(Applicant::getLastName)
                .optional(Applicant::getMiddleName)
                .optional(Applicant::getEmail)
                .optional(Applicant::getPhoneNumber)
                .optional(Applicant::getLanguagePreferenceWelsh)
                .optional(Applicant::getAgreedToReceiveEmails)
            .done()
            .done();
    }

    private AboutToStartOrSubmitResponse<CaseData, State> roundTripStart(CaseDetails<CaseData, State> details) {
        details.getData().setSetInAboutToStart(START_CALLBACK_MARKER);
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(details.getData())
            .build();
    }

    private AboutToStartOrSubmitResponse<CaseData, State> roundTripSubmit(
        CaseDetails<CaseData, State> details,
        CaseDetails<CaseData, State> before
    ) {
        details.getData().setSetInAboutToSubmit(SUBMIT_CALLBACK_MARKER);
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(details.getData())
            .state(details.getState())
            .build();
    }

    private AboutToStartOrSubmitResponse<CaseData, State> roundTripMidEvent(
        CaseDetails<CaseData, State> details,
        CaseDetails<CaseData, State> before
    ) {
        details.getData().setSetInMidEvent(MID_EVENT_MARKER);
        return AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(details.getData())
            .build();
    }
}
