package uk.gov.hmcts.reform.fpl.model.common;

import ccd.sdk.types.CaseField;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class C2DocumentBundle {
    @CaseField(label = "Upload a file")
    private final DocumentReference document;
    @CaseField(label = "Description")
    private final String description;
    private final String uploadedDateTime;
    private final String author;
}
