package uk.gov.hmcts.divorce.divorcecase.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * A service-owned type bound through setters only, with no creator constructor.
 */
@Data
@NoArgsConstructor
public class RoundTripNotification {

    @CCD(label = "Notification id")
    private String id;

    @CCD(label = "Notification status")
    private String status;

    @CCD(label = "Notification recipient")
    private String recipient;
}
