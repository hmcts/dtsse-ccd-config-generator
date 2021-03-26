package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Builder
@EqualsAndHashCode
public class CaseRole {

  String id;
  String name;
  String description;

  public static class CaseRoleBuilder {

  }
}
