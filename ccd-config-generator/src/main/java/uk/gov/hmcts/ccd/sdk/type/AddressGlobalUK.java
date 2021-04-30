package uk.gov.hmcts.ccd.sdk.type;

import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@SuperBuilder
@Jacksonized
@ComplexType(name = "AddressGlobalUK", generate = false)
public class AddressGlobalUK extends Address {
}
