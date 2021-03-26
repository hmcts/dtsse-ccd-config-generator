package uk.gov.hmcts.reform.fpl.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import uk.gov.hmcts.ccd.sdk.api.HasCaseRole;

@AllArgsConstructor
@Getter
public enum CaseRoles implements HasCaseRole {

  CREATOR(
    "[CREATOR]",
    "Creator role",
    "Creator description");

  private final String role;
  private final String name;
  private final String description;
}
