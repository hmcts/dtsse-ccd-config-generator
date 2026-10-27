package uk.gov.hmcts.ccd.sdk.converter.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.model.ClusteredFieldRef;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

class FieldClustererTest {

  private FieldModel text(String id, String label, List<String> access) {
    return FieldModel.builder()
        .id(id)
        .javaName(id)
        .fieldType("Text")
        .javaType("String")
        .label(label)
        .accessClassNames(access)
        .overlayTags(Set.of())
        .build();
  }

  private FieldModel text(String id, String label) {
    return text(id, label, List.of());
  }

  @Test
  void foldsHomogeneousNumberedFamilyIntoOneComplexType() {
    List<FieldModel> fields = List.of(
        text("caseTitle", "Case title"),
        text("applicant1FirstName", "First name"),
        text("applicant1LastName", "Last name"),
        text("applicant2FirstName", "First name"),
        text("applicant2LastName", "Last name"));

    FieldClusterer.Result result =
        new FieldClusterer(new GapCollector()).cluster(fields, Set.of("CaseData"));

    // One synthesized complex type named from the base, with the de-prefixed members.
    assertThat(result.synthesizedTypes()).hasSize(1);
    ComplexTypeModel applicant = result.synthesizedTypes().get(0);
    assertThat(applicant.getId()).isEqualTo("Applicant");
    assertThat(applicant.getMembers()).extracting(FieldModel::getId)
        .containsExactly("firstName", "lastName");

    // The flat member fields are gone from CaseData; two @JsonUnwrapped parents replace them.
    assertThat(result.caseFields()).extracting(FieldModel::getId)
        .containsExactly("caseTitle", "applicant1", "applicant2");
    assertThat(result.caseFields())
        .filteredOn(f -> f.getUnwrapPrefix() != null)
        .extracting(FieldModel::getUnwrapPrefix)
        .containsExactly("applicant1", "applicant2");

    // Each flat leaf maps to a complex reference for the config emitter.
    ClusteredFieldRef ref = result.refs().get("applicant1FirstName");
    assertThat(ref.getParentGetter()).isEqualTo("getApplicant1");
    assertThat(ref.getClusterType()).isEqualTo("Applicant");
    assertThat(ref.getMemberGetter()).isEqualTo("getFirstName");
  }

  @Test
  void leavesFamilyFlatWhenMemberSetsDiffer() {
    List<FieldModel> fields = List.of(
        text("applicant1FirstName", "First name"),
        text("applicant1LastName", "Last name"),
        text("applicant2FirstName", "First name"),
        text("applicant2Email", "Email"));

    GapCollector gaps = new GapCollector();
    FieldClusterer.Result result = new FieldClusterer(gaps).cluster(fields, Set.of());

    assertThat(result.synthesizedTypes()).isEmpty();
    assertThat(result.caseFields()).hasSize(4);
    assertThat(result.refs()).isEmpty();
    assertThat(gaps.getEntries()).anyMatch(g -> g.getDetail().contains("differing member sets"));
  }

  @Test
  void leavesFamilyFlatWhenAccessDiffersAcrossInstances() {
    List<FieldModel> fields = List.of(
        text("applicant1FirstName", "First name", List.of("CaseworkerAccess")),
        text("applicant1LastName", "Last name", List.of("CaseworkerAccess")),
        text("applicant2FirstName", "First name", List.of("CitizenAccess")),
        text("applicant2LastName", "Last name", List.of("CitizenAccess")));

    GapCollector gaps = new GapCollector();
    FieldClusterer.Result result = new FieldClusterer(gaps).cluster(fields, Set.of());

    assertThat(result.synthesizedTypes()).isEmpty();
    assertThat(gaps.getEntries())
        .anyMatch(g -> g.getDetail().contains("type/metadata/access"));
  }

  @Test
  void leavesSingleInstanceUnclustered() {
    List<FieldModel> fields = List.of(
        text("applicant1FirstName", "First name"),
        text("applicant1LastName", "Last name"));

    FieldClusterer.Result result = new FieldClusterer(new GapCollector()).cluster(fields, Set.of());

    assertThat(result.synthesizedTypes()).isEmpty();
    assertThat(result.caseFields()).hasSize(2);
  }

  @Test
  void disambiguatesClusterNameAgainstExistingType() {
    List<FieldModel> fields = List.of(
        text("applicant1FirstName", "First name"),
        text("applicant1LastName", "Last name"),
        text("applicant2FirstName", "First name"),
        text("applicant2LastName", "Last name"));

    FieldClusterer.Result result = new FieldClusterer(new GapCollector())
        .cluster(fields, Set.of("Applicant"));

    assertThat(result.synthesizedTypes().get(0).getId()).isEqualTo("Applicant2");
  }
}
