package uk.gov.hmcts.divorce.simplecase.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleCaseLink {

    @CCD(label = "Linked case reference")
    private String caseReference;

    @CCD(label = "Linked case type")
    private String caseType;
}
