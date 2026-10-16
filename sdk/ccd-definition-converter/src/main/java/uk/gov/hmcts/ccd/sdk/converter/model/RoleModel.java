package uk.gov.hmcts.ccd.sdk.converter.model;

import lombok.Builder;
import lombok.Value;

/**
 * A user role or case role, destined for a constant on the generated UserRole enum
 * (implementing the SDK's HasRole).
 */
@Value
@Builder
public class RoleModel {

  /** The role string, e.g. "caseworker-ia" or "[CREATOR]". */
  String id;

  /** The generated enum constant name, e.g. "CASEWORKER_IA" or "CREATOR". */
  String javaConstant;

  /**
   * Case-type-level CRUD from AuthorisationCaseType, returned by getCaseTypePermissions().
   * Empty string when the role appears only on other Authorisation sheets.
   */
  String caseTypePermissions;

  /**
   * Whether the role is a case role ({@code [BRACKETED]} form, CaseRoles sheet).
   */
  boolean caseRole;

  /** Case role name/description, where the CaseRoles sheet provides them. */
  String name;
  String description;
}
