package uk.gov.hmcts.ccd.sdk.api;

import java.util.Set;
import lombok.Data;

@Data
public class ComplexTypeAuthorisation<R extends HasRole> {
  private final String caseFieldId;
  private final String listElementCode;
  private final Set<Permission> permissions;
  private final R role;
}
