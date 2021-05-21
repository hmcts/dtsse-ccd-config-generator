package uk.gov.hmcts.reform.fpl.model;

import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.BulkScan;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HearingPreferences {
    @CCD(label = "Do you want some Welsh?", access = {BulkScan.class})
    private String welsh;
    private String interpreter;
    private Set<Refreshment> refreshments;
}
