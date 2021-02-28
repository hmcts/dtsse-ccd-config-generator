package uk.gov.hmcts.reform.fpl.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.types.CCD;
import uk.gov.hmcts.reform.fpl.model.common.JudgeAndLegalAdvisor;

import javax.validation.constraints.Future;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor

public class HearingBooking {
    private final String type;
    private final String typeDetails;
    private final String venue;
        @Future(message = "Enter a start date in the future")
    @CCD(hint = "Use 24 hour format")
    private final LocalDateTime startDate;
        @Future(message = "Enter an end date in the future")
    @CCD(hint = "Use 24 hour format")
    private final LocalDateTime endDate;
    private final List<String> hearingNeedsBooked;
    private final String hearingNeedsDetails;
    private final JudgeAndLegalAdvisor judgeAndLegalAdvisor;

    public boolean hasDatesOnSameDay() {
        return this.startDate.toLocalDate().isEqual(this.endDate.toLocalDate());
    }
}
