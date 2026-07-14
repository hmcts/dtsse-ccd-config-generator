package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.SneakyThrows;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.HasAccessControl;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

public class AuthorisationCaseFieldGeneratorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void resolvesClassAccessDefaultsAndFieldOverrides() {
    ResolvedCCDConfig<ClassAccessData, TestState, TestRole> config = new ResolvedCCDConfig<>(
        ClassAccessData.class,
        TestState.class,
        TestRole.class,
        Map.of(),
        ImmutableSet.copyOf(TestState.values())
    );
    ConfigBuilderImpl<ClassAccessData, TestState, TestRole> builder = new ConfigBuilderImpl<>(config);
    builder.caseType("ClassAccess", "Class access", "Class access test");
    builder.omitCaseHistory();

    new AuthorisationCaseFieldGenerator<ClassAccessData, TestState, TestRole>()
        .write(tmp.getRoot(), builder.build());

    assertThat(fieldPermissions(TestRole.DEFAULT))
        .containsExactlyInAnyOrder(
            Map.entry("defaultField", "CR"),
            Map.entry("additionalField", "CR")
        );
    assertThat(fieldPermissions(TestRole.ADDITIONAL))
        .containsExactly(Map.entry("additionalField", "U"));
    assertThat(fieldPermissions(TestRole.REPLACEMENT))
        .containsExactly(Map.entry("replacementField", "D"));
    assertThat(allFieldIds())
        .doesNotContain("optOutField", "ignoredField");
  }

  @Test
  public void canDisableEventTabAndSearchFieldAuthorisationInference() {
    ResolvedCCDConfig<InferredData, TestState, TestRole> config = new ResolvedCCDConfig<>(
        InferredData.class,
        TestState.class,
        TestRole.class,
        Map.of(),
        ImmutableSet.copyOf(TestState.values())
    );
    ConfigBuilderImpl<InferredData, TestState, TestRole> builder = new ConfigBuilderImpl<>(config);
    builder.caseType("ExplicitAccess", "Explicit access", "Explicit access test");
    builder.omitCaseHistory();
    builder.omitFieldAuthorisationInference();
    builder.event("edit")
        .forAllStates()
        .grant(CRU, TestRole.DEFAULT)
        .fields()
        .optional(InferredData::getValue)
        .done();

    new AuthorisationCaseFieldGenerator<InferredData, TestState, TestRole>()
        .write(tmp.getRoot(), builder.build());

    assertThat(new File(tmp.getRoot(),
        "AuthorisationCaseField/" + TestRole.DEFAULT.getRole() + ".json"))
        .doesNotExist();
  }

  private List<Map.Entry<String, String>> fieldPermissions(TestRole role) {
    return rows(role).stream()
        .map(row -> Map.entry((String) row.get("CaseFieldID"), (String) row.get("CRUD")))
        .toList();
  }

  private List<String> allFieldIds() {
    return List.of(TestRole.values()).stream()
        .flatMap(role -> rows(role).stream())
        .map(row -> (String) row.get("CaseFieldID"))
        .toList();
  }

  @SneakyThrows
  private List<Map<String, Object>> rows(TestRole role) {
    File output = new File(tmp.getRoot(), "AuthorisationCaseField/" + role.getRole() + ".json");
    return output.exists()
        ? MAPPER.readValue(output, new TypeReference<>() {})
        : List.of();
  }

  @CCD(access = DefaultAccess.class)
  private static class ClassAccessData {

    @CCD(label = "Default")
    private String defaultField;

    @CCD(access = AdditionalAccess.class)
    private String additionalField;

    @CCD(access = ReplacementAccess.class, inheritAccessFromParent = false)
    private String replacementField;

    @CCD(inheritAccessFromParent = false)
    private String optOutField;

    @CCD(ignore = true)
    private String ignoredField;
  }

  private static class InferredData {
    private String value;

    public String getValue() {
      return value;
    }
  }

  public static class DefaultAccess implements HasAccessControl {

    @Override
    public SetMultimap<HasRole, Permission> getGrants() {
      return grants(TestRole.DEFAULT, Permission.CR);
    }
  }

  public static class AdditionalAccess implements HasAccessControl {

    @Override
    public SetMultimap<HasRole, Permission> getGrants() {
      return grants(TestRole.ADDITIONAL, Set.of(Permission.U));
    }
  }

  public static class ReplacementAccess implements HasAccessControl {

    @Override
    public SetMultimap<HasRole, Permission> getGrants() {
      return grants(TestRole.REPLACEMENT, Set.of(Permission.D));
    }
  }

  private static SetMultimap<HasRole, Permission> grants(TestRole role, Set<Permission> permissions) {
    SetMultimap<HasRole, Permission> grants = HashMultimap.create();
    grants.putAll(role, permissions);
    return grants;
  }

  private enum TestState {
    Open
  }

  private enum TestRole implements HasRole {
    DEFAULT("caseworker-default"),
    ADDITIONAL("caseworker-additional"),
    REPLACEMENT("caseworker-replacement");

    private final String role;

    TestRole(String role) {
      this.role = role;
    }

    @Override
    public String getRole() {
      return role;
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }
}
