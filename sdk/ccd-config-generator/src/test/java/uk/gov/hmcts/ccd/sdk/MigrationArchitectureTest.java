package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.CCDCollectionValue;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

public class MigrationArchitectureTest {

  @Test
  public void sharesOneComponentAcrossTypedRegionalGroups() {
    List<ResolvedCCDConfig<?, ?, ?>> resolved = CCDDefinitionGenerator.loadConfigs(List.of(
        new FirstFoundation(),
        new SecondFoundation(),
        new SharedComponent()
    ));

    assertThat(resolved).extracting(ResolvedCCDConfig::getCaseType)
        .containsExactlyInAnyOrder("FIRST", "SECOND");

    ResolvedCCDConfig<?, ?, ?> first = resolved.stream()
        .filter(config -> config.getCaseType().equals("FIRST"))
        .findFirst()
        .orElseThrow();
    ResolvedCCDConfig<?, ?, ?> second = resolved.stream()
        .filter(config -> config.getCaseType().equals("SECOND"))
        .findFirst()
        .orElseThrow();

    assertThat(first.getSchemaProfile()).isEqualTo(FirstProfile.class);
    assertThat(first.getTypes()).containsKeys(ProfiledComplex.class, CollectionValue.class);
    assertThat(first.getApplicableRoles()).extracting(HasRole::getRole)
        .containsExactly(TestRole.FIRST.getRole());
    assertThat(second.getSchemaProfile()).isEqualTo(SecondProfile.class);
    assertThat(second.getTypes()).containsKey(CollectionValue.class)
        .doesNotContainKey(ProfiledComplex.class)
        .doesNotContainKey(CollectionWrapper.class);
    assertThat(second.getApplicableRoles()).extracting(HasRole::getRole)
        .containsExactly(TestRole.SECOND.getRole());
  }

  @Test
  public void resolvesProfileSpecificFieldMetadata() throws Exception {
    Field regional = SharedData.class.getDeclaredField("regional");

    assertThat(FieldUtils.getCCD(regional, FirstProfile.class)).get()
        .extracting(CCD::label)
        .isEqualTo("First label");
    assertThat(FieldUtils.getFieldId(regional, null, FirstProfile.class)).isEqualTo("regional");
    assertThat(FieldUtils.getCCD(regional, SecondProfile.class)).get()
        .extracting(CCD::label)
        .isEqualTo("Second label");
    assertThat(FieldUtils.getFieldId(regional, null, SecondProfile.class))
        .isEqualTo("secondRegional");
  }

  @Test
  public void keepsTheMostDerivedCompatibleRedeclarationOnce() {
    assertThat(FieldUtils.getCaseFields(CompatibleChild.class))
        .extracting(Field::getDeclaringClass, Field::getName)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(CompatibleChild.class, "value"),
            org.assertj.core.groups.Tuple.tuple(CompatibleParent.class, "parentOnly")
        );
  }

  @Test
  public void rejectsConflictingRedeclaredFieldTypes() {
    assertThatThrownBy(() -> FieldUtils.getCaseFields(ConflictingChild.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Conflicting Java types for redeclared CCD field value");
  }

  @Test
  public void doesNotFallBackToAnInheritedFieldWhenTheMostDerivedDeclarationIsProfileExcluded() {
    assertThat(FieldUtils.getCaseFields(ProfileExcludedChild.class, FirstProfile.class))
        .extracting(Field::getName)
        .doesNotContain("value");
  }

  private static class FirstFoundation implements CCDConfig<SharedData, TestState, TestRole> {
    @Override
    public String groupingKey() {
      return "FIRST";
    }

    @Override
    public void configure(ConfigBuilder<SharedData, TestState, TestRole> builder) {
      builder.caseType("FIRST", "First", "First");
      builder.schemaProfile(FirstProfile.class);
      builder.applicableRoles(TestRole.FIRST);
    }
  }

  private static class SecondFoundation implements CCDConfig<SharedData, TestState, TestRole> {
    @Override
    public String groupingKey() {
      return "SECOND";
    }

    @Override
    public void configure(ConfigBuilder<SharedData, TestState, TestRole> builder) {
      builder.caseType("SECOND", "Second", "Second");
      builder.schemaProfile(SecondProfile.class);
      builder.applicableRoles(TestRole.SECOND);
    }
  }

  private static class SharedComponent implements CCDConfig<SharedData, TestState, TestRole> {
    @Override
    public List<String> groupingKeys() {
      return List.of("FIRST", "SECOND");
    }

    @Override
    public void configure(ConfigBuilder<SharedData, TestState, TestRole> builder) {
      builder.omitCaseHistory();
    }
  }

  private static class SharedData {
    @CCD(label = "First label", includeInProfiles = FirstProfile.class)
    @CCD(id = "secondRegional", label = "Second label", includeInProfiles = SecondProfile.class)
    private String regional;

    @CCD(label = "Profiled", includeInProfiles = FirstProfile.class)
    private ProfiledComplex profiled;

    private List<CollectionWrapper> values;
  }

  private static final class FirstProfile {
  }

  private static final class SecondProfile {
  }

  private static class CompatibleParent {
    private String value;
    private String parentOnly;
  }

  private static class CompatibleChild extends CompatibleParent {
    private String value;
  }

  private static class ConflictingParent {
    private String value;
  }

  private static class ConflictingChild extends ConflictingParent {
    private Integer value;
  }

  private static class ProfileExcludedParent {
    private String value;
  }

  private static class ProfileExcludedChild extends ProfileExcludedParent {
    @CCD(excludeFromProfiles = FirstProfile.class)
    private String value;
  }

  @ComplexType(name = "Profiled", generate = true)
  private static class ProfiledComplex {
    private String value;
  }

  @CCDCollectionValue
  private static class CollectionWrapper {
    private String id;
    private CollectionValue value;
  }

  @ComplexType(name = "CollectionValue", generate = true)
  private static class CollectionValue {
    private String text;
  }

  private enum TestState {
    Open
  }

  private enum TestRole implements HasRole {
    FIRST,
    SECOND;

    @Override
    public String getRole() {
      return "caseworker-" + name().toLowerCase();
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }
}
