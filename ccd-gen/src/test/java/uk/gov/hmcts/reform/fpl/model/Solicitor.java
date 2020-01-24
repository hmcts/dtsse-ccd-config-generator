package uk.gov.hmcts.reform.fpl.model;

import uk.gov.hmcts.ccd.sdk.types.CaseField;
import uk.gov.hmcts.ccd.sdk.types.ComplexType;
import uk.gov.hmcts.ccd.sdk.types.FieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
@Builder
@AllArgsConstructor
@ComplexType(label = "# Solicitor's details")
public class Solicitor {
    @CaseField(label="DX number")
    private final String dx;

    @NotBlank(message = "Enter the solicitor's full name")
    @CaseField(label="Solicitor's full name", hint="Local authority or authorised person")
    private final String name;

    @NotBlank(message = "Enter the solicitor's email")
    @CaseField(label="Solicitor's email", hint="For example, joe.bloggs@la.gov.uk",
    type=FieldType.Email)
    private final String email;

    @CaseField(label="Solicitor's mobile number", hint="For example, 07665 545327")
    private final String mobile;

    @CaseField(label="Solicitor's reference")
    private final String reference;

    @CaseField(label="Solicitor's telephone number",
            hint="For example, 020 2772 5772",
    type= FieldType.PhoneUK)
    private final String telephone;
}
