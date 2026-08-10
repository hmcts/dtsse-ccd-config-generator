package uk.gov.hmcts.ccd.sdk.api;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class CaseRoleToAccessProfile<R extends HasRole> {
  /**
   * The role this mapping is keyed on. Typed {@link HasRole} rather than {@code R} so the SDK can
   * derive a mapping for a role reached through {@link CCDAccessGroup}, whose members are
   * {@code HasRole}-typed because that interface is not generic in the role class. Only
   * {@link HasRole#getRole()} is ever read from it.
   */
  private HasRole role;
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
      result.authorisation = new ArrayList<>();
      result.accessProfiles = new ArrayList<>();
      result.caseAccessCategories = new ArrayList<>();
      return result;
    }

    public CaseRoleToAccessProfileBuilder<R> authorisation(String... auth) {
      authorisation.addAll(List.of(auth));

      return this;
    }

    public CaseRoleToAccessProfileBuilder<R> accessProfiles(String... profiles) {
      accessProfiles.addAll(List.of(profiles));

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
