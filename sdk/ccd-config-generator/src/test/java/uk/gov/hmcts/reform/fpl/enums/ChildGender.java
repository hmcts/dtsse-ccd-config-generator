package uk.gov.hmcts.reform.fpl.enums;

import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;

import java.util.stream.Stream;

@RequiredArgsConstructor
public enum ChildGender {

    @CCD(label = "boy", hint = "something")
    BOY("Boy"),

    @CCD(hint = "girl")
    GIRL("Girl"),

    @CCD
    OTHER("They identify in another way"),

    MULTI("Multiple genders");

    private final String label;

    public static ChildGender fromLabel(String label) {
        return Stream.of(ChildGender.values())
            .filter(gender -> gender.label.equalsIgnoreCase(label))
            .findFirst()
            .orElse(OTHER);
    }

    public String getLabel() {
        return label;
    }


}
