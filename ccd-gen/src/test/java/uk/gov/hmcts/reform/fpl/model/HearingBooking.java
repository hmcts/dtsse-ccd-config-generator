package uk.gov.hmcts.reform.fpl.model;

import ccd.sdk.types.CaseField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.reform.fpl.model.common.JudgeAndLegalAdvisor;
import uk.gov.hmcts.reform.fpl.validation.groups.HearingBookingDetailsGroup;
import uk.gov.hmcts.reform.fpl.validation.interfaces.time.HasEndDateAfterStartDate;
import uk.gov.hmcts.reform.fpl.validation.interfaces.time.TimeNotMidnight;

import javax.validation.constraints.Future;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@HasEndDateAfterStartDate(groups = HearingBookingDetailsGroup.class)
public class HearingBooking {
    @CaseField(label = "Type of hearing")
    private final String type;
    @CaseField(label = "Give details")
    private final String typeDetails;
    @CaseField(label = "Venue")
    private final String venue;
    @TimeNotMidnight(message = "Enter a valid start time", groups = HearingBookingDetailsGroup.class)
    @Future(message = "Enter a start date in the future", groups = HearingBookingDetailsGroup.class)
    @CaseField(label = "Start date and time", hint = "Use 24 hour format")
    private final LocalDateTime startDate;
    @TimeNotMidnight(message = "Enter a valid end time", groups = HearingBookingDetailsGroup.class)
    @Future(message = "Enter an end date in the future", groups = HearingBookingDetailsGroup.class)
    @CaseField(label = "End date and time", hint = "Use 24 hour format")
    private final LocalDateTime endDate;
    @CaseField(label = "Hearing needs booked")
    private final List<String> hearingNeedsBooked;
    @CaseField(label = "Give details")
    private final String hearingNeedsDetails;
    private final JudgeAndLegalAdvisor judgeAndLegalAdvisor;

    public boolean hasDatesOnSameDay() {
        return this.startDate.toLocalDate().isEqual(this.endDate.toLocalDate());
    }
}
