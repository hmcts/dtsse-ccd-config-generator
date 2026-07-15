package uk.gov.hmcts.ccd.sdk.converter.model;

import lombok.Builder;
import lombok.Value;

/**
 * One flat {@code AuthorisationComplexType} grant, reproduced via
 * {@code ConfigBuilder.grantComplexType(field, listElementCode, permissions, role)}. The linker
 * expands the input's flat / {@code UserRoles[]} / nested {@code AccessControl[]} shapes into these
 * per-role rows (the same flattening {@code ccd-definition-processor} performs at build time), so
 * the SDK's flat generator output equals the imported form of every shape.
 */
@Value
@Builder
public class ComplexTypeAuthModel {

  /** The complex CaseField the grant restricts (CaseFieldID). */
  String caseFieldId;

  /** The dotted sub-field path within the complex field (ListElementCode). */
  String listElementCode;

  /** The role the grant is scoped to (RoleName / UserRole / AccessProfile). */
  String role;

  /**
   * The CRUD string, e.g. {@code "CRU"}.
   */
  String crud;
}
