package uk.gov.hmcts.reform.fpl.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.model.common.JudgeAndLegalAdvisor;

import javax.validation.constraints.Future;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor

public class HearingBooking {
    @CCD(displayOrder = 1)
    private final String venue;
    @CCD(displayOrder = 8)
    private final String type;
    @CCD(displayOrder = 5)
    private final String typeDetails;
        @Future(message = "Enter a start date in the future")
    @CCD(hint = "Use 24 hour format", displayOrder = 7)
    private final LocalDateTime startDate;
        @Future(message = "Enter an end date in the future")
    @CCD(hint = "Use 24 hour format", displayOrder = 2)
    private final LocalDateTime endDate;
    @CCD(displayOrder = 3)
    private final List<String> hearingNeedsBooked;
    @CCD(displayOrder = 4)
    private final String hearingNeedsDetails;
    @CCD(displayOrder = 6)
    private final JudgeAndLegalAdvisor judgeAndLegalAdvisor;

    public boolean hasDatesOnSameDay() {
        return this.startDate.toLocalDate().isEqual(this.endDate.toLocalDate());
    }
}
