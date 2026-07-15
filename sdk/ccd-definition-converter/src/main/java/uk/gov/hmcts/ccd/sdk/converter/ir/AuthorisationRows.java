package uk.gov.hmcts.ccd.sdk.converter.ir;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Expands the two array shorthands the {@code Authorisation*} sheets use to grant many roles in a
 * single row, matching {@code ccd-definition-processor}'s {@code access-control-transformer.js}:
 * <ul>
 *   <li>a {@code UserRoles} array with a row-level {@code CRUD}
 *       ({@code {…, UserRoles:[[A],[B]], CRUD}}); and</li>
 *   <li>an {@code AccessControl} array carrying per-element {@code CRUD}
 *       ({@code {…, AccessControl:[{UserRoles:[…], CRUD}, …]}}).</li>
 * </ul>
 * A plain flat row (single {@code UserRole}/{@code AccessProfile} + {@code CRUD}) is returned as a
 * single entry. Roles are the array elements verbatim (e.g. {@code [SOLICITORA]}), matching the
 * flat role string the generator emits.
 */
public final class AuthorisationRows {

  private AuthorisationRows() {
  }

  /**
   * The (role → CRUD) grants a single authorisation row expresses, expanding any array shorthand.
   * A role appearing more than once keeps the last CRUD, matching the processor's flattening.
   *
   * @param row the authorisation row
   * @return role to CRUD, in declaration order; empty when the row grants nothing
   */
  public static Map<String, String> roleGrants(SheetRow row) {
    Map<String, String> grants = new LinkedHashMap<>();
    String flatRole = row.getString(Columns.ACCESS_PROFILE, Columns.USER_ROLE).orElse(null);
    String crud = row.getString(Columns.CRUD).orElse(null);
    if (flatRole != null && crud != null) {
      grants.put(flatRole, crud);
    } else if (crud != null) {
      Object userRoles = row.getColumns().get(Columns.USER_ROLES);
      if (userRoles instanceof List) {
        for (Object role : (List<?>) userRoles) {
          if (role != null) {
            grants.put(String.valueOf(role), crud);
          }
        }
      }
    }
    Object accessControl = row.getColumns().get(Columns.ACCESS_CONTROL);
    if (accessControl instanceof List) {
      for (Object element : (List<?>) accessControl) {
        if (!(element instanceof Map)) {
          continue;
        }
        Map<?, ?> ac = (Map<?, ?>) element;
        Object roles = ac.get(Columns.USER_ROLES);
        Object elementCrud = ac.get(Columns.CRUD);
        if (roles instanceof List && elementCrud != null) {
          for (Object role : (List<?>) roles) {
            if (role != null) {
              grants.put(String.valueOf(role), String.valueOf(elementCrud));
            }
          }
        }
      }
    }
    return grants;
  }
}
