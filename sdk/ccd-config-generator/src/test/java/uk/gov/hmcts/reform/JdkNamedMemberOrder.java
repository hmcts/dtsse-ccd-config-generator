package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * The intermediate complex type of {@link JdkNamedMemberCaseType}, named {@code value} on its
 * parent so that descending through it makes {@code CaseEventToComplexTypesGenerator} resolve the
 * name {@code value} against a member's own type — see {@link JdkNamedMemberWrapper#value}.
 */
@Data
@ComplexType(name = "JdkNamedMemberOrder", generate = true)
public class JdkNamedMemberOrder {

  @CCD(label = "Document name")
  private String documentName;
}
