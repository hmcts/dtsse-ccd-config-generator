package uk.gov.hmcts.ccd.sdk.converter.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.model.ClusteredFieldRef;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;

class CaseDataGrouperTest {

  private static FieldModel field(String id) {
    return FieldModel.builder().id(id).javaName(id).fieldType("Text").javaType("String")
        .overlayTags(Set.of()).build();
  }

  private static List<FieldModel> fields(String... ids) {
    List<FieldModel> out = new ArrayList<>();
    for (String id : ids) {
      out.add(field(id));
    }
    return out;
  }

  private static List<String> ids(List<FieldModel> fields) {
    return fields.stream().map(FieldModel::getId).toList();
  }

  private static Map<String, List<String>> membersByGroup(CaseDataGrouper.Result result) {
    return result.groups().stream().collect(Collectors.toMap(
        ComplexTypeModel::getJavaClassName, g -> ids(g.getMembers()),
        (a, b) -> a, LinkedHashMap::new));
  }

  @Test
  void groupsFieldsSharingALeadingWordUnderAPrefix() {
    CaseDataGrouper.Result result = new CaseDataGrouper(3).group(
        fields("appellantGivenNames", "isAdult", "appellantFamilyName", "appellantDateOfBirth"),
        Set.of(), new LinkedHashSet<>());

    // The container takes the first member's place; members drop the prefix.
    assertThat(ids(result.caseFields())).containsExactly("appellant", "isAdult");
    FieldModel container = result.caseFields().get(0);
    assertThat(container.getJavaType()).isEqualTo("Appellant");
    assertThat(container.getUnwrapPrefix()).isEqualTo("appellant");
    assertThat(membersByGroup(result))
        .containsExactly(Map.entry("Appellant", List.of("givenNames", "familyName", "dateOfBirth")));

    ClusteredFieldRef ref = result.refs().get("appellantGivenNames");
    assertThat(ref.getParentGetter()).isEqualTo("getAppellant");
    assertThat(ref.getClusterType()).isEqualTo("Appellant");
    assertThat(ref.getMemberGetter()).isEqualTo("getGivenNames");
  }

  @Test
  void belowTheMinimumSizeNothingIsGrouped() {
    List<FieldModel> input = fields("appellantGivenNames", "appellantFamilyName");
    CaseDataGrouper.Result result =
        new CaseDataGrouper(3).group(input, Set.of(), new LinkedHashSet<>());

    assertThat(result.caseFields()).isSameAs(input);
    assertThat(result.groups()).isEmpty();
    assertThat(result.refs()).isEmpty();
  }

  @Test
  void stopWordsNeverFormAGroup() {
    CaseDataGrouper.Result result = new CaseDataGrouper(3).group(
        fields("isAdult", "isDetained", "isRemote", "hasSponsor", "hasLawyer", "hasAppeal"),
        Set.of(), new LinkedHashSet<>());

    assertThat(result.groups()).isEmpty();
  }

  @Test
  void prefixExtendsWhileEveryMemberSharesTheNextWord() {
    CaseDataGrouper.Result result = new CaseDataGrouper(3).group(
        fields("homeOfficeReferenceNumber", "homeOfficeDecisionDate", "homeOfficeLetter"),
        Set.of(), new LinkedHashSet<>());

    assertThat(ids(result.caseFields())).containsExactly("homeOffice");
    assertThat(result.caseFields().get(0).getUnwrapPrefix()).isEqualTo("homeOffice");
    assertThat(membersByGroup(result)).containsExactly(
        Map.entry("HomeOffice", List.of("referenceNumber", "decisionDate", "letter")));
  }

  @Test
  void underscoreIdsGroupWithoutAPrefixAndKeepTheirIds() {
    CaseDataGrouper.Result result = new CaseDataGrouper(3).group(
        fields("progress_caseOfficer_a", "progress_caseOfficer_b", "progress_caseOfficer_c",
            "flag_one", "flag_two", "flag_three"),
        Set.of(), new LinkedHashSet<>());

    // Two leading segments when they are shared widely enough, else the first segment alone.
    assertThat(ids(result.caseFields())).containsExactly("progressCaseOfficer", "flag");
    assertThat(result.caseFields()).extracting(FieldModel::getUnwrapPrefix).containsOnly("");
    assertThat(membersByGroup(result)).containsExactly(
        Map.entry("ProgressCaseOfficer",
            List.of("progress_caseOfficer_a", "progress_caseOfficer_b", "progress_caseOfficer_c")),
        Map.entry("Flag", List.of("flag_one", "flag_two", "flag_three")));
    assertThat(result.refs().get("flag_two").getMemberGetter()).isEqualTo("getFlag_two");
  }

  @Test
  void pinnedFieldsAndAcronymRemaindersStayFlat() {
    CaseDataGrouper.Result result = new CaseDataGrouper(3).group(
        fields("witnessName", "witnessAddress", "witnessPhone", "witnessEmail",
            "hearingDate", "hearingVenue", "hearingTime", "hearingHMCId"),
        Set.of("witnessAddress"), new LinkedHashSet<>());

    // hearingHMCId: Jackson would read getHMCId back as hmcid, so the member cannot be stripped.
    assertThat(ids(result.caseFields()))
        .containsExactly("witness", "witnessAddress", "hearing", "hearingHMCId");
    assertThat(membersByGroup(result)).containsExactly(
        Map.entry("Witness", List.of("name", "phone", "email")),
        Map.entry("Hearing", List.of("date", "venue", "time")));
  }

  @Test
  void groupClassNamesAvoidTakenTypeNames() {
    Set<String> used = new LinkedHashSet<>(Set.of("Hearing"));
    CaseDataGrouper.Result result = new CaseDataGrouper(3).group(
        fields("hearingDate", "hearingVenue", "hearingTime"), Set.of(), used);

    assertThat(result.caseFields().get(0).getJavaType()).isEqualTo("HearingFields");
    assertThat(used).contains("HearingFields");
  }
}
