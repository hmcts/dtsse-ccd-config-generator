package uk.gov.hmcts.reform;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Exercises the unregistered-role mappings.
 *
 * <p>{@link ConfigBuilder#roleToAccessProfile(String)} maps organisational / IDAM role names that
 * are not case-type {@link UserRole}s — they appear in {@code RoleToAccessProfiles} but must not be
 * registered (no {@code AuthorisationCaseType} row, no {@code UserRole} enum entry).
 *
 * <p>{@link ConfigBuilder#emitCaseRoleJurisdiction()} opts the {@code CaseRoles} sheet into stamping
 * the {@code JurisdictionID} column (from {@link ConfigBuilder#jurisdiction}); the case role comes
 * from {@link UserRole#CCD_SOLICITOR} ({@code [SOLICITOR]}).
 */
@Component
public class RoleMappingsCaseType implements CCDConfig<RoleMappingsCaseData, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<RoleMappingsCaseData, State, UserRole> builder) {
    builder.caseType("RoleMappings", "RoleMappings", "Role mappings case type");
    builder.jurisdiction("ROLEMAP", "Role mappings", "Role mappings jurisdiction");

    // Opt in to the JurisdictionID column on CaseRoles.
    builder.emitCaseRoleJurisdiction();

    // Unregistered organisational / IDAM roles — mapped by plain string, never registered.
    builder.roleToAccessProfile("caseworker-rolemap-system")
        .accessProfiles("caseworker-rolemap-system", "caseworker-rolemap-caseofficer");

    builder.roleToAccessProfile("citizen")
        .accessProfiles("citizen")
        .legacyIdamRole();
  }
}
