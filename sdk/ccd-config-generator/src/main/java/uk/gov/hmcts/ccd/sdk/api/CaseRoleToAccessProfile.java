package uk.gov.hmcts.ccd.sdk.api;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class CaseRoleToAccessProfile<R extends HasRole> {
  /**
   * The role this mapping is keyed on, when declared against a {@link HasRole} constant; {@code null}
   * for a mapping declared against a plain role name. Typed {@link HasRole} rather than {@code R}
   * because {@code RoleToAccessProfiles} also maps roles outside the case's role class.
   */
  private HasRole role;
  /**
   * The {@code RoleName} this mapping is emitted under. Always set: taken from
   * {@link HasRole#getRole()} for a typed mapping, or verbatim for one declared against a plain
   * string (organisational / IDAM roles that are not case-type {@code UserRole}s and therefore must
   * not be registered as one).
   */
  private String roleName;
  private List<String> authorisation;
  private boolean readonly;
  private List<String> accessProfiles;
  private boolean disabled;
  private List<String> caseAccessCategories;
  private boolean legacyIdamRole;

  public static class CaseRoleToAccessProfileBuilder<R extends HasRole> {

    public static <R extends HasRole> CaseRoleToAccessProfileBuilder<R> builder(HasRole role) {
      CaseRoleToAccessProfileBuilder<R> result = CaseRoleToAccessProfile.builder();
      result.role = role;
      result.roleName = role.getRole();
      result.authorisation = new ArrayList<>();
      result.accessProfiles = new ArrayList<>(List.of(role.getRole()));
      result.caseAccessCategories = new ArrayList<>();
      return result;
    }

    /**
     * Start a mapping keyed on a literal role name rather than a {@link HasRole} constant. The name
     * is emitted verbatim as the {@code RoleName} and no {@code UserRole} is registered, so no
     * {@code AuthorisationCaseType} row is produced for it.
     *
     * @param roleName the organisational / IDAM role name to map, e.g. {@code caseworker-ia-system}
     */
    public static <R extends HasRole> CaseRoleToAccessProfileBuilder<R> builder(String roleName) {
      CaseRoleToAccessProfileBuilder<R> result = CaseRoleToAccessProfile.builder();
      result.roleName = roleName;
      result.authorisation = new ArrayList<>();
      result.accessProfiles = new ArrayList<>(List.of(roleName));
      result.caseAccessCategories = new ArrayList<>();
      return result;
    }

    public CaseRoleToAccessProfileBuilder<R> authorisation(String... auth) {
      authorisation.addAll(List.of(auth));

      return this;
    }

    /**
     * Set the access profiles, <em>replacing</em> the default seeded from the role name
     * rather than adding to it. Unlike the other varargs methods here, calling this twice keeps only
     * the last set.
     */
    public CaseRoleToAccessProfileBuilder<R> accessProfiles(String... profiles) {
      accessProfiles = new ArrayList<>(List.of(profiles));

      return this;
    }

    public CaseRoleToAccessProfileBuilder<R> caseAccessCategories(String... categories) {
      caseAccessCategories.addAll(List.of(categories));

      return this;
    }

    public CaseRoleToAccessProfileBuilder<R> readonly() {
      readonly = true;

      return this;
    }

    public CaseRoleToAccessProfileBuilder<R> disabled() {
      disabled = true;

      return this;
    }

    public CaseRoleToAccessProfileBuilder<R> legacyIdamRole() {
      legacyIdamRole = true;

      return this;
    }
  }
}
