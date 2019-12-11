package uk.gov.hmcts.reform.fpl.model.common;

import ccd.sdk.types.CaseField;
import ccd.sdk.types.ComplexType;
import ccd.sdk.types.FieldType;
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
