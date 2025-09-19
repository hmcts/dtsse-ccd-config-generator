package uk.gov.hmcts.reform.fpl.model.emergencyprotectionorder;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Data
@Builder
@AllArgsConstructor(onConstructor_ = {@JsonCreator})
public class EPOPhrase {
    @CCD(searchable = false)
    private String includePhrase;
}
