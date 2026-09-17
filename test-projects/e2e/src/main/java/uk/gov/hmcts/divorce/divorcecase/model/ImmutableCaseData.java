package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Data
@Builder
@AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.DISABLED))
@NoArgsConstructor(force = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImmutableCaseData {

    private final String firstValue;
    private final String secondValue;

    @CCD(ignore = true)
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private final int primitiveNumber;

    @CCD(ignore = true)
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private final boolean primitiveFlag;
}
