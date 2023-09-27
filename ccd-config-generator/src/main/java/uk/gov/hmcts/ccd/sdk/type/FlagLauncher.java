package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@ComplexType(generate = false)
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlagLauncher {
}
