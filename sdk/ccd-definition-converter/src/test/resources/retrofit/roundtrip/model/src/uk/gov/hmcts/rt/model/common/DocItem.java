package uk.gov.hmcts.rt.model.common;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * A concrete, non-generic collection wrapper — like SSCS's {@code Hearing} or ET's
 * {@code DocumentTypeItem}. {@code List<DocItem>} does NOT descend under the SDK's
 * {@code hasGenerics()} rule, so retrofit writes {@code @CCD(typeParameterOverride = "Document")} on
 * the {@code documents} field. Annotated {@code @ComplexType(generate = false)} so the SDK does not
 * emit a spurious {@code ComplexTypes/DocItem.json} for the wrapper itself (it is a pure carrier;
 * the real element type the definition wants is the inner {@code Document}).
 */
@Data
@ComplexType(generate = false)
public class DocItem {

  private String id;

  private Document value;
}
