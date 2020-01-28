package uk.gov.hmcts.reform.fpl.model.common;

import uk.gov.hmcts.ccd.sdk.types.CCD;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class C2DocumentBundle {
    @CCD(label = "Upload a file")
    private final DocumentReference document;
    @CCD(label = "Description")
    private final String description;
    private final String uploadedDateTime;
    private final String author;
}
