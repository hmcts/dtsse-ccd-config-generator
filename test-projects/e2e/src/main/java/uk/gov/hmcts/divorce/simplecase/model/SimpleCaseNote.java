package uk.gov.hmcts.divorce.simplecase.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleCaseNote {

    @CCD(label = "Author")
    private String author;

    @CCD(label = "Date")
    private LocalDateTime timestamp;

    @CCD(label = "Note", typeOverride = FieldType.TextArea)
    private String note;
}
