package uk.gov.hmcts.reform.fpl.model.common;

import uk.gov.hmcts.ccd.sdk.types.CaseField;
import uk.gov.hmcts.ccd.sdk.types.ComplexType;
import uk.gov.hmcts.ccd.sdk.types.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@ComplexType(name="Recitals")
public class Recital {
    @CaseField(label = "Recital title")
    private final String title;
    @CaseField(label = "Description", type = FieldType.TextArea)
    private final String description;
}
