package uk.gov.hmcts.reform.fpl.enums;

import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;

import java.util.stream.Stream;

@RequiredArgsConstructor
public enum ChildGender {

    @CCD(label = "boy")
    BOY("Boy"),

    @CCD
    GIRL("Girl"),

    OTHER("They identify in another way");

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
