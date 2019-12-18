package uk.gov.hmcts.reform.fpl.model.common;

import ccd.sdk.types.ComplexType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.SuperBuilder;


@Data
@SuperBuilder
@AllArgsConstructor
@ComplexType(name = "UploadSwetDocument")
public class SWETDocument extends Document {
}
