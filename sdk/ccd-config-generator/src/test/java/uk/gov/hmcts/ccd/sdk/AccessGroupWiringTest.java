package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * Asserts that an access group declared on a role enum constant joins up with the event that writes
 * the {@code OrganisationPolicy} the runtime reads.
 *
 * <p>Group access spans four generated sheets. {@code AccessTypeRole.CaseAssignedRoleField} names a
 * case role; at runtime {@code CaseAccessGroupUtils} scans case data for an
 * {@code OrganisationPolicy} carrying that role in its {@code OrgPolicyCaseAssignedRole} and
 * substitutes the organisation ID it finds there into {@code CaseAccessGroupIDTemplate}. So the role
 * only means anything if some <em>event</em> actually writes that policy — which is what
 * {@code CaseEventToComplexTypes} records. The group role must additionally be mapped in
 * {@code RoleToAccessProfiles}, and must <em>not</em> appear in {@code CaseRoles} /
 * {@code AuthorisationCaseType}.
 *
 * <p>Every one of those sheets is individually valid when the join between them is broken, so the
 * per-file golden fixtures in {@link E2EConfigGenerationTests} stay green while group access
 * silently grants nothing. These tests read the same generated output those fixtures pin, and assert
 * across it. (See {@code docs/testing-strategy.md}: golden files remain the primary cover for
 * generated shape; this adds the cross-sheet invariant they cannot express.)
 */
@SpringBootTest(properties = { "config-generator.basePackage=uk.gov.hmcts" })
@RunWith(SpringRunner.class)
public class AccessGroupWiringTest {

  private static final String CASE_TYPE = "CARE_SUPERVISION_EPO";
  private static final String ORG_POLICY_ROLE_ELEMENT = "OrgPolicyCaseAssignedRole";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @ClassRule
  public static TemporaryFolder tmp = new TemporaryFolder();

  @Autowired
  private CCDDefinitionGenerator generator;

  @Before
  public void before() {
    generator.generateAllCaseTypesToJSON(tmp.getRoot());
  }

  /**
   * The AccessTypeRole row is derived from the role the group is attached to: the role supplies
   * OrganisationalRoleName, the group supplies everything else.
   */
  @Test
  public void derivesAnAccessTypeRoleFromTheRoleTheGroupIsAttachedTo() {
    assertThat(sheet("AccessTypeRole.json"))
        .as("no AccessTypeRole was derived from UserRole.CASE_ACCESS_APPROVER's access group")
        .anySatisfy(row -> {
          assertThat(row).containsEntry("AccessTypeID", "SOLICITOR_ORG_POLICY");
          assertThat(row).containsEntry("OrganisationalRoleName", "caseworker-approver");
          assertThat(row).containsEntry("GroupRoleName", "caseworker-approver-group");
          assertThat(row).containsEntry("CaseAssignedRoleField", "[SOLICITOR]");
        });
  }

  /**
   * The wiring under test: the role named by CaseAssignedRoleField must be written by an event as an
   * OrganisationPolicy's OrgPolicyCaseAssignedRole. Without that, no case ever carries a policy for
   * this access type and no CaseAccessGroups entry is ever seeded.
   */
  @Test
  public void everyCaseAssignedRoleFieldIsWrittenByAnEvent() {
    List<Map<String, Object>> policyRoleFields = orgPolicyCaseAssignedRoleFields();

    assertThat(policyRoleFields)
        .as("no event writes an OrgPolicyCaseAssignedRole, so nothing can seed CaseAccessGroups")
        .isNotEmpty();

    List<String> rolesWrittenByEvents = policyRoleFields.stream()
        .map(field -> String.valueOf(field.get("DefaultValue")))
        .distinct()
        .toList();

    assertThat(sheet("AccessTypeRole.json")).isNotEmpty().allSatisfy(row ->
        assertThat(rolesWrittenByEvents)
            .as("AccessTypeRole %s names CaseAssignedRoleField '%s', but no event writes that role as "
                + "an OrganisationPolicy's OrgPolicyCaseAssignedRole, so CaseAccessGroupUtils would "
                + "find no policy to read the organisation ID from", row.get("AccessTypeID"),
                row.get("CaseAssignedRoleField"))
            .contains(String.valueOf(row.get("CaseAssignedRoleField"))));
  }

  /**
   * The role is written as a default value on the policy's complex sub-field, so a case picks it up
   * on event submission without the caseworker or a callback setting it. Recorded here because the
   * per-event golden files each show one event in isolation; this is the fixture-wide statement that
   * the policy is reachable from more than one place, including a nested complex.
   */
  @Test
  public void writesTheRoleAsADefaultValueOnEveryEventCarryingThePolicy() {
    assertThat(orgPolicyCaseAssignedRoleFields())
        .as("OrgPolicyCaseAssignedRole is configured on both a top-level and a nested "
            + "OrganisationPolicy in the fixture")
        .hasSizeGreaterThanOrEqualTo(2)
        .allSatisfy(field -> assertThat(field)
            .as("event %s writes %s.%s with no DefaultValue, so the policy is created without a role "
                + "and the access group cannot match it", field.get("CaseEventID"),
                field.get("CaseFieldID"), field.get("ListElementCode"))
            .containsEntry("DefaultValue", "[SOLICITOR]"));
  }

  /**
   * The definition store performs no referential check on GroupRoleName, so an unmapped group role
   * imports cleanly and then resolves to no access profile. The SDK derives the mapping so that
   * cannot happen.
   */
  @Test
  public void mapsEveryGroupRoleToAnAccessProfile() {
    List<Map<String, Object>> accessProfileRows = sheet("RoleToAccessProfiles.json");

    assertThat(sheet("AccessTypeRole.json")).isNotEmpty().allSatisfy(row ->
        assertThat(accessProfileRows)
            .as("group role %s has no RoleToAccessProfiles row, so the role assignment PRM mints for "
                + "it grants no access", row.get("GroupRoleName"))
            .anySatisfy(profile -> {
              assertThat(profile).containsEntry("RoleName", row.get("GroupRoleName"));
              assertThat(String.valueOf(profile.get("AccessProfiles"))).isNotBlank();
            }));
  }

  /**
   * A group role must not be a member of the case's role class. That class is enum-iterated to emit
   * CaseRoles and AuthorisationCaseType, and an organisational group role belongs in neither — hence
   * {@code GroupRole} being a separate enum reached through {@code HasRole}.
   */
  @Test
  public void doesNotEmitGroupRolesAsCaseRoles() {
    List<String> caseRoles = sheet("CaseRoles.json").stream()
        .map(row -> String.valueOf(row.get("ID")))
        .toList();
    List<String> authorisedRoles = sheet("AuthorisationCaseType.json").stream()
        .map(row -> String.valueOf(row.get("UserRole")))
        .toList();

    assertThat(sheet("AccessTypeRole.json")).isNotEmpty().allSatisfy(row -> {
      String groupRole = String.valueOf(row.get("GroupRoleName"));
      assertThat(caseRoles)
          .as("group role %s was emitted as a CaseRole", groupRole)
          .doesNotContain(groupRole);
      assertThat(authorisedRoles)
          .as("group role %s was emitted as an AuthorisationCaseType role", groupRole)
          .doesNotContain(groupRole);
    });
  }

  /**
   * The template is what turns the organisation ID read off the policy into the
   * {@code caseAccessGroupId} that {@code CaseAccessGroupsMatcher} compares role assignments
   * against, and the definition store rejects one that is not prefixed with the case type's
   * jurisdiction.
   */
  @Test
  public void prefixesTheGroupIdTemplateWithTheJurisdiction() {
    String jurisdiction = String.valueOf(sheet("CaseType.json").get(0).get("JurisdictionID"));

    assertThat(sheet("AccessTypeRole.json")).isNotEmpty().allSatisfy(row ->
        assertThat(String.valueOf(row.get("CaseAccessGroupIDTemplate")))
            .as("AccessTypeRolesValidator rejects a CaseAccessGroupIDTemplate that is not prefixed "
                + "with the case type's jurisdiction")
            .startsWith(jurisdiction + ":")
            .contains("$ORGID$"));
  }

  /** Every OrgPolicyCaseAssignedRole element written by any event, from any depth of complex. */
  @SneakyThrows
  private List<Map<String, Object>> orgPolicyCaseAssignedRoleFields() {
    Path root = new File(tmp.getRoot(), CASE_TYPE + "/CaseEventToComplexTypes").toPath();
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(Files::isRegularFile)
          .flatMap(file -> readSheet(file).stream())
          .filter(row -> String.valueOf(row.get("ListElementCode")).endsWith(ORG_POLICY_ROLE_ELEMENT))
          .toList();
    }
  }

  private List<Map<String, Object>> sheet(String name) {
    return readSheet(new File(tmp.getRoot(), CASE_TYPE + "/" + name).toPath());
  }

  @SneakyThrows
  private static List<Map<String, Object>> readSheet(Path path) {
    return MAPPER.readValue(path.toFile(),
        MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
  }
}
