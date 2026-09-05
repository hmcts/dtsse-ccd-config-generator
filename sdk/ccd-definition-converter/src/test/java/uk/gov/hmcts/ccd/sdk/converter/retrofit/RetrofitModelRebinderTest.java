package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FixedListModel;

/**
 * Pins {@link RetrofitModelRebinder}'s FixedList collision drop (finding F1): a FixedList whose ID
 * names a top-level type ALREADY declared in the model — whether a reused enum ({@code ClaimType}) or
 * a non-enum class ({@code Party}, like fpl's {@code HearingVenue} address class) — is NOT
 * regenerated, because a fresh {@code enum} of that simple name in the model package would be a
 * duplicate-type compile error. A FixedList with no same-named model type is kept.
 */
class RetrofitModelRebinderTest {

  private static final Path MODEL_ROOT =
      Path.of("src/test/resources/retrofit/model/src").toAbsolutePath();

  @Test
  void dropsFixedListsCollidingWithAnExistingModelTypeButKeepsFreshOnes() {
    ModelSourceIndex index = ModelSourceIndex.parse(MODEL_ROOT);
    ModelSourceIndex.Type root = index.byFqn("uk.gov.hmcts.example.model.CaseData").orElseThrow();
    PropertyResolver.Resolution resolution = new PropertyResolver(index).resolve(root);

    CaseTypeModel model = CaseTypeModel.builder()
        .caseFields(List.of())
        .fixedLists(List.of(
            // Collides with the model's enum ClaimType (reused).
            FixedListModel.builder().id("ClaimType").items(List.of()).build(),
            // Collides with the model's CLASS Party (fpl's HearingVenue-class shape) — must be dropped.
            FixedListModel.builder().id("Party").items(List.of()).build(),
            // No same-named model type — generated fresh as normal.
            FixedListModel.builder().id("FreshList").items(List.of()).build()))
        .build();

    CaseTypeModel rebound = new RetrofitModelRebinder(index, resolution, root).rebind(model);

    assertThat(rebound.getFixedLists())
        .extracting(FixedListModel::getId)
        .containsExactly("FreshList")
        .doesNotContain("ClaimType", "Party");
  }

  private static final Path BUG4_ROOT =
      Path.of("src/test/resources/retrofit/bug4model/src").toAbsolutePath();

  @Test
  void marksUnwrappedLeavesWithNoResolvableParentGetterUnplaceable() {
    // Bug4: a leaf reached through a prefix-less @JsonUnwrapped parent whose Lombok getter is
    // suppressed (@Getter(AccessLevel.NONE)) with either a differently-named hand-written accessor
    // (getRenamedParent, not getFinalDecisionParent) or NONE must be marked unplaceable — no
    // compilable CaseData::getParent reference exists. A leaf under a normal unwrapped parent (whose
    // @Data getter IS generated) stays placeable via a clustered ref.
    ModelSourceIndex index = ModelSourceIndex.parse(BUG4_ROOT);
    ModelSourceIndex.Type root = index.byFqn("uk.gov.hmcts.b4.model.CaseData").orElseThrow();
    PropertyResolver.Resolution resolution = new PropertyResolver(index).resolve(root);

    // The resolver walks the three unwrapped parents and flattens their leaves verbatim (prefix-less).
    List<FieldModel> caseFields = List.of(
        FieldModel.builder().id("finalDecisionField").javaName("finalDecisionField")
            .fieldType("Text").build(),
        FieldModel.builder().id("deprecatedField").javaName("deprecatedField")
            .fieldType("Text").build(),
        FieldModel.builder().id("workAllocationField").javaName("workAllocationField")
            .fieldType("Text").build());
    CaseTypeModel model = CaseTypeModel.builder()
        .caseFields(caseFields)
        .fixedLists(List.of())
        .build();

    CaseTypeModel rebound = new RetrofitModelRebinder(index, resolution, root).rebind(model);

    // The two suppressed-getter families are unplaceable; the normal one is not.
    assertThat(rebound.getUnplaceableFieldIds())
        .containsExactlyInAnyOrder("finalDecisionField", "deprecatedField");
    // The placeable leaf still gets a clustered ref through its (Lombok-generated) parent getter.
    assertThat(rebound.getClusteredFieldRefs()).containsKey("workAllocationField");
    assertThat(rebound.getClusteredFieldRefs().get("workAllocationField").getParentGetter())
        .isEqualTo("getWorkAllocation");
    // The unplaceable leaves get NO clustered ref (they must not be referenced at all).
    assertThat(rebound.getClusteredFieldRefs())
        .doesNotContainKeys("finalDecisionField", "deprecatedField");
  }
}
