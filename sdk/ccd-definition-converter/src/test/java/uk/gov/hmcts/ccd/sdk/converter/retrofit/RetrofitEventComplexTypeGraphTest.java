package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.converter.link.EventComplexTypeResolver;
import uk.gov.hmcts.ccd.sdk.converter.model.EventComplexTypeGroup;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;

/**
 * Pins the retrofit binder's reference-resolution fix: a {@code CaseEventToComplexTypes} member chain
 * must bind to the class a complex field is <em>actually declared as</em> in the team's model, with
 * that class's real getters — never the SDK-predefined type of the same complex-type ID (probate
 * conflict #4 / prl bug class 6), a similarly-named synthesised sibling (fpl {@code Allocation} vs
 * {@code AllocationProposal}, prl {@code PartyDetails} vs {@code PartyDetailsApplicant}), and never a
 * getter the real class does not declare (a definition-only label member → the group cannot derive and
 * falls back to a row passthrough rather than emitting a broken reference).
 *
 * <p>Drives {@link EventComplexTypeResolver} through {@link RetrofitEventComplexTypeGraph} over a
 * throwaway model source tree, the same wiring the linker uses in retrofit mode.
 */
class RetrofitEventComplexTypeGraphTest {

  private static void write(Path root, String pkgPath, String simpleName, String body)
      throws Exception {
    Path dir = root.resolve(pkgPath);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(simpleName + ".java"), body);
  }

  /** The SDK-predefined complex-type IDs the resolver reflects when a field is genuinely SDK-typed. */
  private static final Map<String, String> PREDEFINED = Map.of(
      "ChangeOrganisationRequest", "uk.gov.hmcts.ccd.sdk.type.ChangeOrganisationRequest",
      "Organisation", "uk.gov.hmcts.ccd.sdk.type.Organisation");

  /** A model where a complex field's declared type is the team's OWN same-shaped class. */
  private EventComplexTypeResolver resolverFor(Path src) throws Exception {
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    PropertyResolver.Resolution resolution =
        new PropertyResolver(index).resolve(index.byFqn("m.CaseData").orElseThrow());
    return new EventComplexTypeResolver(
        List.of(), PREDEFINED, new RetrofitEventComplexTypeGraph(index, resolution));
  }

  private EventComplexTypeGroup.Member resolve(
      EventComplexTypeResolver resolver, FieldModel field, String lec) {
    Optional<EventComplexTypeGroup.Member> member = resolver.resolve(
        resolver.rootNode(field), lec, "optional", null, null, null, null);
    assertThat(member).as("member '%s' must resolve", lec).isPresent();
    return member.get();
  }

  @Test
  void bindsToTheTeamsOwnClassNotTheSdkPredefinedTypeOfTheSameId(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // The team declares its OWN Organisation (getter getOrganisationID) and ChangeOrganisationRequest,
    // both same-named as SDK-predefined complex types. The field's chain must bind to the team classes.
    write(src, "m/access", "Organisation", "package m.access;\n"
        + "import com.fasterxml.jackson.annotation.JsonProperty;\nimport lombok.Data;\n@Data\n"
        + "public class Organisation {\n  @JsonProperty(\"OrganisationID\") private String organisationID;\n}\n");
    write(src, "m/access", "ChangeOrganisationRequest", "package m.access;\n"
        + "import com.fasterxml.jackson.annotation.JsonProperty;\nimport lombok.Data;\n@Data\n"
        + "public class ChangeOrganisationRequest {\n"
        + "  @JsonProperty(\"OrganisationToAdd\") private Organisation organisationToAdd;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n"
        + "import m.access.ChangeOrganisationRequest;\n@Data\npublic class CaseData {\n"
        + "  private ChangeOrganisationRequest changeOrganisationRequestField;\n}\n");

    EventComplexTypeResolver resolver = resolverFor(src);
    FieldModel field = FieldModel.builder()
        .id("changeOrganisationRequestField").javaName("changeOrganisationRequestField")
        .fieldType("ChangeOrganisationRequest").build();

    EventComplexTypeGroup.Member member =
        resolve(resolver, field, "OrganisationToAdd.OrganisationID");
    // The hop and leaf name the team's classes by FQN (may live in any sub-package), and the leaf
    // getter is the team's getOrganisationID — NOT the SDK's getOrganisationId.
    assertThat(member.getHops()).singleElement().satisfies(hop -> {
      assertThat(hop.getDeclaringType().getModelFqn())
          .isEqualTo("m.access.ChangeOrganisationRequest");
      assertThat(hop.getGetter()).isEqualTo("getOrganisationToAdd");
    });
    assertThat(member.getLeafType().getModelFqn()).isEqualTo("m.access.Organisation");
    assertThat(member.getLeafType().getPredefinedFqn()).isNull();
    assertThat(member.getLeafGetter()).isEqualTo("getOrganisationID");
  }

  @Test
  void bindsToTheDeclaredTypeNotASimilarlyNamedSibling(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // The field is declared as PartyDetails; a similarly-named PartyDetailsApplicant sibling also
    // exists. The chain must walk the DECLARED PartyDetails, not the sibling.
    write(src, "m", "PartyDetails", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class PartyDetails {\n  private String firstName;\n}\n");
    write(src, "m", "PartyDetailsApplicant", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class PartyDetailsApplicant {\n  private String firstName;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private PartyDetails applicantsFL401;\n}\n");

    EventComplexTypeResolver resolver = resolverFor(src);
    FieldModel field = FieldModel.builder()
        .id("applicantsFL401").javaName("applicantsFL401").fieldType("PartyDetails").build();

    EventComplexTypeGroup.Member member = resolve(resolver, field, "firstName");
    assertThat(member.getHops()).isEmpty();
    assertThat(member.getLeafType().getModelFqn()).isEqualTo("m.PartyDetails");
    assertThat(member.getLeafGetter()).isEqualTo("getFirstName");
  }

  @Test
  void descendsIntoTheDeclaredCollectionElementType(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // A collection field declared List<CollectionMember<PartyDetails>>: the chain binds to the element
    // type PartyDetails and the root opens the element-typed scope.
    write(src, "m", "CollectionMember", "package m;\npublic class CollectionMember<T> {\n"
        + "  private T value;\n  public T getValue() { return value; }\n}\n");
    write(src, "m", "PartyDetails", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class PartyDetails {\n  private String firstName;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport java.util.List;\nimport lombok.Data;\n@Data\n"
        + "public class CaseData {\n  private List<CollectionMember<PartyDetails>> respondents;\n}\n");

    EventComplexTypeResolver resolver = resolverFor(src);
    FieldModel field = FieldModel.builder()
        .id("respondents").javaName("respondents")
        .fieldType("Collection").fieldTypeParameter("PartyDetails").build();

    assertThat(resolver.rootElementType(field).getModelFqn()).isEqualTo("m.PartyDetails");
    EventComplexTypeGroup.Member member = resolve(resolver, field, "firstName");
    assertThat(member.getLeafType().getModelFqn()).isEqualTo("m.PartyDetails");
    assertThat(member.getLeafGetter()).isEqualTo("getFirstName");
  }

  @Test
  void fallsBackWhenAMemberHasNoJavaBackingOnTheRealClass(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // A definition-only label member the real wired class does not declare: the resolver returns empty
    // so the group cannot derive and stays a verbatim row passthrough — never a broken getter ref
    // homed onto a richer synthesised companion (prl bug class 5).
    write(src, "m", "WithdrawApplication", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class WithdrawApplication {\n  private String withDrawApplication;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private WithdrawApplication withDrawApplicationData;\n}\n");

    EventComplexTypeResolver resolver = resolverFor(src);
    FieldModel field = FieldModel.builder()
        .id("withDrawApplicationData").javaName("withDrawApplicationData")
        .fieldType("WithdrawApplication").build();

    // The real member resolves; the label-only member with no Java backing does not.
    assertThat(resolver.resolve(resolver.rootNode(field), "withDrawApplication",
        "mandatory", null, null, null, null)).isPresent();
    assertThat(resolver.resolve(resolver.rootNode(field), "withDrawApplicationHeadingLabel",
        "mandatory", null, null, null, null)).isEmpty();
  }

  @Test
  void fallsBackToTheSdkPathWhenTheFieldIsGenuinelySdkTyped(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // A field genuinely typed as the SDK's own ChangeOrganisationRequest (no team class shadows it):
    // rootNode has no model binding, so the resolver falls back to the SDK-predefined type-id walk and
    // the leaf getter is the SDK's getOrganisationId.
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n"
        + "import uk.gov.hmcts.ccd.sdk.type.ChangeOrganisationRequest;\n@Data\n"
        + "public class CaseData {\n"
        + "  private ChangeOrganisationRequest changeOrganisationRequestField;\n}\n");

    EventComplexTypeResolver resolver = resolverFor(src);
    FieldModel field = FieldModel.builder()
        .id("changeOrganisationRequestField").javaName("changeOrganisationRequestField")
        .fieldType("ChangeOrganisationRequest").build();

    EventComplexTypeGroup.Member member =
        resolve(resolver, field, "OrganisationToAdd.OrganisationID");
    assertThat(member.getLeafType().getPredefinedFqn())
        .isEqualTo("uk.gov.hmcts.ccd.sdk.type.Organisation");
    assertThat(member.getLeafType().getModelFqn()).isNull();
    assertThat(member.getLeafGetter()).isEqualTo("getOrganisationId");
  }
}
