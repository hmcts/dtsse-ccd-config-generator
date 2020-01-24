package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CaseField;
import uk.gov.hmcts.ccd.sdk.types.FieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class Grounds {
    @NotNull(message = "Select at least one option for how this case meets the threshold criteria")
    @Size(min = 1, message = "Select at least one option for how this case meets the threshold criteria")
    @CaseField(typeParameter = "GroundsList", label = "The child concerned is suffering or is likely to suffer significant harm because they are:", hint = "Select all that apply")
    private final List<@NotBlank(message = "Select at least one option for how this case meets the threshold criteria")
        String> thresholdReason;
    @NotBlank(message = "Enter details of how the case meets the threshold criteria")
    @CaseField(type = FieldType.TextArea, label = "Give details of how this case meets the threshold criteria")
    private final String thresholdDetails;
}
