package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

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
    return resolverFor(src, RetrofitPlannedSynthesis.empty());
  }

  /** The same wiring, with the patch's planned field synthesis fed to the graph. */
  private EventComplexTypeResolver resolverFor(Path src, RetrofitPlannedSynthesis planned)
      throws Exception {
    return resolverFor(src, planned, RetrofitPinnedNames.empty());
  }

  /**
   * The same wiring, collecting the {@code @JsonNaming}-derived names the walk relies on into the
   * given sink so a test can assert the graph records exactly what the patch must pin.
   */
  private EventComplexTypeResolver resolverFor(
      Path src, RetrofitPlannedSynthesis planned, RetrofitPinnedNames pinned) throws Exception {
    return resolverFor(src, planned, pinned, RetrofitPlannedHints.empty());
  }

  /**
   * The same wiring again, with the hints the patch will pin on existing members — which the walk must
   * prefer over the parsed declaration, because they cascade onto every event row placing the member.
   */
  private EventComplexTypeResolver resolverFor(Path src, RetrofitPlannedSynthesis planned,
      RetrofitPinnedNames pinned, RetrofitPlannedHints hints) throws Exception {
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    PropertyResolver.Resolution resolution =
        new PropertyResolver(index).resolve(index.byFqn("m.CaseData").orElseThrow());
    return new EventComplexTypeResolver(List.of(), PREDEFINED,
        new RetrofitEventComplexTypeGraph(index, resolution,
            index.byFqn("m.CaseData").orElseThrow(), planned, RetrofitPlannedRetypes.empty(),
            hints, pinned));
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
  void resolvesAMemberTheRetrofitPatchIsAboutToSynthesise(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // The ordering defect (civil's FixedRecoverableCosts.bandLabel): the definition declares a member
    // the parsed class does not, but the SAME run's patch synthesises it as a real field. The walk reads
    // the model as PARSED, so without the plan the row fell back to a verbatim passthrough even though
    // the patched model has the field. With the plan the member resolves to the getter the patch's field
    // name yields, and carries the planned @CCD(hint).
    write(src, "m", "FixedRecoverableCosts", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class FixedRecoverableCosts {\n  private String band;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private FixedRecoverableCosts applicant1DQFixedRecoverableCosts;\n}\n");

    RetrofitPlannedSynthesis planned = RetrofitPlannedSynthesis.empty();
    planned.record("m.FixedRecoverableCosts", FieldModel.builder()
        .id("bandLabel").javaName("bandLabel").fieldType("Text").hint("Planned hint").build());

    EventComplexTypeResolver resolver = resolverFor(src, planned);
    FieldModel field = FieldModel.builder()
        .id("applicant1DQFixedRecoverableCosts").javaName("applicant1DQFixedRecoverableCosts")
        .fieldType("FixedRecoverableCosts").build();

    // The declared member still resolves normally…
    assertThat(resolve(resolver, field, "band").getLeafGetter()).isEqualTo("getBand");
    // …and so does the one the patch adds.
    EventComplexTypeGroup.Member member = resolve(resolver, field, "bandLabel");
    assertThat(member.getHops()).isEmpty();
    assertThat(member.getLeafGetter()).isEqualTo("getBandLabel");
    assertThat(member.getLeafType().getModelFqn()).isEqualTo("m.FixedRecoverableCosts");
    assertThat(member.getDeclaredHint()).isEqualTo("Planned hint");

    // Without the plan the SAME code falls back — the behaviour the fix replaces.
    assertThat(resolverFor(src).resolve(resolverFor(src).rootNode(field), "bandLabel",
        "mandatory", null, null, null, null)).isEmpty();
  }

  @Test
  void reportsTheHintThePatchWillPinOnAnExistingMemberRatherThanTheParsedOne(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // sscs's rip1Document: the team's field carries no @CCD(hint), but the definition's ComplexTypes row
    // has a HintText, so the SAME run's patch is about to pin it. The hint does not stay on that row —
    // the SDK cascades it onto every CaseEventToComplexTypes row placing the member — so a caller
    // deciding a placement's .hintText/.noHintText disposition against the PARSED hint concludes
    // "equal, leave the cascade unset" and emits a HintText the definition never had.
    write(src, "m", "AudioVideo", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class AudioVideo {\n  private String rip1Document;\n  private String other;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private AudioVideo audioVideo;\n}\n");

    RetrofitPlannedHints hints = RetrofitPlannedHints.empty();
    hints.record("m.AudioVideo", "rip1Document", "Document must be PDF formatted");

    EventComplexTypeResolver resolver = resolverFor(
        src, RetrofitPlannedSynthesis.empty(), RetrofitPinnedNames.empty(), hints);
    FieldModel field = FieldModel.builder()
        .id("audioVideo").javaName("audioVideo").fieldType("AudioVideo").build();

    assertThat(resolve(resolver, field, "rip1Document").getDeclaredHint())
        .isEqualTo("Document must be PDF formatted");
    // A member the patch pins no hint on still reports the parsed declaration — absent means "the source
    // stands", not "no hint".
    assertThat(resolve(resolver, field, "other").getDeclaredHint()).isNull();
    // And without the plan the same member reads as unhinted: the behaviour the fix replaces.
    assertThat(resolve(resolverFor(src), field, "rip1Document").getDeclaredHint()).isNull();
  }

  @Test
  void doesNotResolveBeneathAPlannedSynthesisedMemberOfANonComplexType(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // A planned member whose declared type is a SCALAR is a leaf: nothing can be addressed beneath it,
    // so a dotted code through one must still fall back rather than invent a hop.
    write(src, "m", "Party", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class Party {\n  private String name;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private Party applicant;\n}\n");

    RetrofitPlannedSynthesis planned = RetrofitPlannedSynthesis.empty();
    planned.record("m.Party", FieldModel.builder()
        .id("contactEmail").javaName("contactEmail").fieldType("Text").build());

    EventComplexTypeResolver resolver = resolverFor(src, planned);
    FieldModel field = FieldModel.builder()
        .id("applicant").javaName("applicant").fieldType("Party").build();

    // The planned member itself resolves as a leaf…
    assertThat(resolve(resolver, field, "contactEmail").getLeafGetter())
        .isEqualTo("getContactEmail");
    // …but a segment beneath it does not.
    assertThat(resolver.resolve(resolver.rootNode(field), "contactEmail.local",
        "mandatory", null, null, null, null)).isEmpty();
  }

  @Test
  void resolvesBeneathAPlannedSynthesisedMemberIntoTheTeamsOwnClass(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // sscs's supporter.name.firstName shape: the definition declares a member the model does not, the
    // patch synthesises it as a real field, and the type BENEATH it is a class the team already declares
    // — under a camelCase definition id ('name') against a PascalCase class (Name), which only the
    // by-id lookup's case-insensitive fallback binds. The walk must descend PAST the synthesised member
    // and land on the team's Name (real getter getFirstName) rather than fall back: 9 sscs
    // EventToComplexTypes rows were passed through as raw JSON for exactly this reason.
    write(src, "m", "Name", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class Name {\n  private String firstName;\n}\n");
    write(src, "m", "Appeal", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class Appeal {\n  private String benefitType;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private Appeal appeal;\n}\n");

    RetrofitPlannedSynthesis planned = RetrofitPlannedSynthesis.empty();
    planned.record("m.Appeal", FieldModel.builder()
        .id("appellantName").javaName("appellantName").fieldType("name").build());

    EventComplexTypeResolver resolver = resolverFor(src, planned);
    FieldModel field = FieldModel.builder()
        .id("appeal").javaName("appeal").fieldType("Appeal").build();

    EventComplexTypeGroup.Member member = resolve(resolver, field, "appellantName.firstName");
    assertThat(member.getLeafGetter()).isEqualTo("getFirstName");
    assertThat(member.getLeafType().getModelFqn()).isEqualTo("m.Name");
    assertThat(member.getHops()).hasSize(1);
    assertThat(member.getHops().get(0).getGetter()).isEqualTo("getAppellantName");
    assertThat(member.getHops().get(0).getDeclaringType().getModelFqn()).isEqualTo("m.Appeal");
    // The hop is a SCALAR complex member, so no element-typed scope is opened for it.
    assertThat(member.getHops().get(0).getElementType()).isNull();

    // Without the plan the same code falls back — the behaviour the fix replaces.
    EventComplexTypeResolver unplanned = resolverFor(src);
    assertThat(unplanned.resolve(unplanned.rootNode(field), "appellantName.firstName",
        "mandatory", null, null, null, null)).isEmpty();
  }

  @Test
  void resolvesAMemberByItsClassJsonNamingStrategyAndRecordsThePinItRelieson(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // Civil's Address: the class carries @JsonNaming(UpperCamelCaseStrategy), so field addressLine1
    // serialises — and appears in the definition's ListElementCode — as AddressLine1. Both the SDK's
    // PropertyUtils.getPropertyName and this converter's own id rule are naming-strategy BLIND, so the
    // walk must apply the strategy to resolve the segment AND record the reliance so the patch pins the
    // name with an explicit @JsonProperty. Resolving without pinning would emit
    // Address::getAddressLine1 and have the SDK regenerate the id 'addressLine1' — silently changing
    // the CCD field id, a fidelity regression strictly worse than the passthrough it replaces.
    write(src, "m", "Address", "package m;\n"
        + "import com.fasterxml.jackson.databind.PropertyNamingStrategies;\n"
        + "import com.fasterxml.jackson.databind.annotation.JsonNaming;\nimport lombok.Data;\n@Data\n"
        + "@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)\n"
        + "public class Address {\n  private String addressLine1;\n  private String PostCode;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private Address applicantAddress;\n}\n");

    RetrofitPinnedNames pinned = RetrofitPinnedNames.empty();
    EventComplexTypeResolver resolver =
        resolverFor(src, RetrofitPlannedSynthesis.empty(), pinned);
    FieldModel field = FieldModel.builder()
        .id("applicantAddress").javaName("applicantAddress").fieldType("AddressUK").build();

    EventComplexTypeGroup.Member member = resolve(resolver, field, "AddressLine1");
    assertThat(member.getHops()).isEmpty();
    assertThat(member.getLeafType().getModelFqn()).isEqualTo("m.Address");
    // The getter is the JAVA field's, not the strategy's name — the pin is what reconciles the two, and
    // it carries the strategy's own id so the patch has nothing to re-derive.
    assertThat(member.getLeafGetter()).isEqualTo("getAddressLine1");
    assertThat(pinned.idsFor("m.Address")).containsExactly(entry("addressLine1", "AddressLine1"));

    // A field whose own name already equals the segment resolves by the exact rule and is NOT pinned:
    // only names an actual resolved walk depended on are pinned, never every field of the class.
    assertThat(resolve(resolver, field, "PostCode").getLeafGetter()).isEqualTo("getPostCode");
    assertThat(pinned.javaNamesFor("m.Address")).containsExactly("addressLine1");
  }

  @Test
  void leavesAFieldCarryingItsOwnJsonPropertyToThatAnnotation(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // Jackson's precedence: an explicit field-level @JsonProperty overrides the class-level
    // @JsonNaming. The walk must honour that — the field resolves under its @JsonProperty value and
    // NOT under the strategy's name for it, and needs no pin (the annotation is already there).
    write(src, "m", "Address", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonProperty;\n"
        + "import com.fasterxml.jackson.databind.PropertyNamingStrategies;\n"
        + "import com.fasterxml.jackson.databind.annotation.JsonNaming;\nimport lombok.Data;\n@Data\n"
        + "@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)\n"
        + "public class Address {\n"
        + "  @JsonProperty(\"AddressTown\") private String postTown;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private Address applicantAddress;\n}\n");

    RetrofitPinnedNames pinned = RetrofitPinnedNames.empty();
    EventComplexTypeResolver resolver =
        resolverFor(src, RetrofitPlannedSynthesis.empty(), pinned);
    FieldModel field = FieldModel.builder()
        .id("applicantAddress").javaName("applicantAddress").fieldType("AddressUK").build();

    assertThat(resolve(resolver, field, "AddressTown").getLeafGetter()).isEqualTo("getPostTown");
    assertThat(resolver.resolve(resolver.rootNode(field), "PostTown",
        "mandatory", null, null, null, null)).isEmpty();
    assertThat(pinned.isEmpty()).isTrue();
  }

  @Test
  void resolvesAMemberByItsCreatorParameterJsonPropertyAndRecordsThePin(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // fpl's Address: an immutable value class whose @JsonProperty lives on the @JsonCreator
    // CONSTRUCTOR PARAMETERS, not the fields. Jackson honours it for both directions, so the member
    // really does appear in the definition as AddressLine1 — but the SDK's PropertyUtils.getPropertyName
    // reads @JsonProperty only off the field and the read method, so the walk saw nothing and the row
    // fell back to a verbatim passthrough (364 of fpl's EventToComplexTypes fallbacks). Same contract as
    // the @JsonNaming path: resolve AND pin, or the SDK would regenerate the id 'addressLine1'.
    write(src, "m", "Address", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonCreator;\n"
        + "import com.fasterxml.jackson.annotation.JsonProperty;\nimport lombok.Data;\n@Data\n"
        + "public class Address {\n"
        + "  private final String addressLine1;\n  private final String postTown;\n"
        + "  @JsonCreator\n  public Address(@JsonProperty(\"AddressLine1\") String addressLine1,\n"
        + "      @JsonProperty(\"PostTown\") String postTown) {\n"
        + "    this.addressLine1 = addressLine1;\n    this.postTown = postTown;\n  }\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private Address applicantAddress;\n}\n");

    RetrofitPinnedNames pinned = RetrofitPinnedNames.empty();
    EventComplexTypeResolver resolver =
        resolverFor(src, RetrofitPlannedSynthesis.empty(), pinned);
    FieldModel field = FieldModel.builder()
        .id("applicantAddress").javaName("applicantAddress").fieldType("AddressUK").build();

    EventComplexTypeGroup.Member member = resolve(resolver, field, "AddressLine1");
    assertThat(member.getLeafType().getModelFqn()).isEqualTo("m.Address");
    // The getter is the JAVA field's; the pin is what makes the SDK regenerate the parameter's id. The
    // ID is recorded alongside the name because there is no class naming strategy here for the patch to
    // re-derive it from — recording only the name pinned nothing at all and silently changed the id.
    assertThat(pinned.idsFor("m.Address")).containsExactly(entry("addressLine1", "AddressLine1"));
    assertThat(member.getLeafGetter()).isEqualTo("getAddressLine1");

    // Only names an actual resolved walk depended on are pinned — not every creator parameter.
    assertThat(resolve(resolver, field, "PostTown").getLeafGetter()).isEqualTo("getPostTown");
    assertThat(pinned.idsFor("m.Address"))
        .containsOnly(entry("addressLine1", "AddressLine1"), entry("postTown", "PostTown"));
  }

  @Test
  void ignoresACreatorParameterThatDoesNotNameTheFieldItAssigns(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // Matching is by parameter NAME, not position: a creator whose parameter is named for something
    // other than the field must not bind it. Positional matching would silently mis-bind a reordered
    // constructor and pin the wrong id — strictly worse than the passthrough it replaces. Here the
    // field's own @JsonProperty also outranks the parameter's, exactly as it does for Jackson.
    write(src, "m", "Address", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonCreator;\n"
        + "import com.fasterxml.jackson.annotation.JsonProperty;\nimport lombok.Data;\n@Data\n"
        + "public class Address {\n"
        + "  private final String addressLine1;\n"
        + "  @JsonProperty(\"PostTown\") private final String postTown;\n"
        + "  @JsonCreator\n  public Address(@JsonProperty(\"AddressLine1\") String line1,\n"
        + "      @JsonProperty(\"AddressTown\") String postTown) {\n"
        + "    this.addressLine1 = line1;\n    this.postTown = postTown;\n  }\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private Address applicantAddress;\n}\n");

    RetrofitPinnedNames pinned = RetrofitPinnedNames.empty();
    EventComplexTypeResolver resolver =
        resolverFor(src, RetrofitPlannedSynthesis.empty(), pinned);
    FieldModel field = FieldModel.builder()
        .id("applicantAddress").javaName("applicantAddress").fieldType("AddressUK").build();

    // Parameter 'line1' names no field, so AddressLine1 does not resolve.
    assertThat(resolver.resolve(resolver.rootNode(field), "AddressLine1",
        "mandatory", null, null, null, null)).isEmpty();
    // The field's own @JsonProperty wins over the creator parameter's AddressTown, and needs no pin.
    assertThat(resolver.resolve(resolver.rootNode(field), "AddressTown",
        "mandatory", null, null, null, null)).isEmpty();
    assertThat(resolve(resolver, field, "PostTown").getLeafGetter()).isEqualTo("getPostTown");
    assertThat(pinned.isEmpty()).isTrue();
  }

  @Test
  void ignoresAConstructorParameterWhenTheConstructorIsNotAJsonCreator(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // Without @JsonCreator, Jackson never consults the constructor's parameter annotations for
    // deserialisation naming and the SDK certainly does not — the definition segment would be the
    // field's own name, so binding it here would pin an id the input never carried.
    write(src, "m", "Address", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonProperty;\nimport lombok.Data;\n@Data\n"
        + "public class Address {\n  private final String addressLine1;\n"
        + "  public Address(@JsonProperty(\"AddressLine1\") String addressLine1) {\n"
        + "    this.addressLine1 = addressLine1;\n  }\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private Address applicantAddress;\n}\n");

    RetrofitPinnedNames pinned = RetrofitPinnedNames.empty();
    EventComplexTypeResolver resolver =
        resolverFor(src, RetrofitPlannedSynthesis.empty(), pinned);
    FieldModel field = FieldModel.builder()
        .id("applicantAddress").javaName("applicantAddress").fieldType("AddressUK").build();

    assertThat(resolver.resolve(resolver.rootNode(field), "AddressLine1",
        "mandatory", null, null, null, null)).isEmpty();
    assertThat(pinned.isEmpty()).isTrue();
  }

  @Test
  void resolvesASnakeCaseStrategyMemberExactlyAsJacksonTranslatesIt(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // The other statically-evaluable strategy, with Jackson's own translation (a '_' before each
    // upper-case RUN, everything lower-cased): postTown → post_town, and the run-collapsing case
    // hmctsDXNumber → hmcts_dxnumber (NOT hmcts_dx_number). NamingStrategyTest cross-checks the
    // translation against real Jackson; this pins that the WALK uses it.
    write(src, "m", "Address", "package m;\n"
        + "import com.fasterxml.jackson.databind.PropertyNamingStrategies;\n"
        + "import com.fasterxml.jackson.databind.annotation.JsonNaming;\nimport lombok.Data;\n@Data\n"
        + "@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)\n"
        + "public class Address {\n  private String postTown;\n  private String hmctsDXNumber;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private Address applicantAddress;\n}\n");

    RetrofitPinnedNames pinned = RetrofitPinnedNames.empty();
    EventComplexTypeResolver resolver =
        resolverFor(src, RetrofitPlannedSynthesis.empty(), pinned);
    FieldModel field = FieldModel.builder()
        .id("applicantAddress").javaName("applicantAddress").fieldType("AddressUK").build();

    assertThat(resolve(resolver, field, "post_town").getLeafGetter()).isEqualTo("getPostTown");
    assertThat(resolve(resolver, field, "hmcts_dxnumber").getLeafGetter())
        .isEqualTo("getHmctsDXNumber");
    assertThat(pinned.javaNamesFor("m.Address"))
        .containsExactly("postTown", "hmctsDXNumber");
  }

  @Test
  void refusesToGuessACustomNamingStrategyItCannotEvaluate(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // probate's @JsonNaming(RegularCaseNamingStrategy.class): a team's own strategy class is arbitrary
    // Java the converter cannot evaluate without running it. It must NOT be guessed at (a wrong guess
    // would pin a wrong @JsonProperty and change the CCD id); the member simply does not resolve and
    // the row keeps its pre-existing verbatim passthrough.
    write(src, "m", "RegularCaseNamingStrategy", "package m;\n"
        + "import com.fasterxml.jackson.databind.PropertyNamingStrategies;\n"
        + "public class RegularCaseNamingStrategy extends PropertyNamingStrategies.NamingBase {\n"
        + "  @Override public String translate(String in) { return in; }\n}\n");
    write(src, "m", "Address", "package m;\n"
        + "import com.fasterxml.jackson.databind.annotation.JsonNaming;\nimport lombok.Data;\n@Data\n"
        + "@JsonNaming(RegularCaseNamingStrategy.class)\n"
        + "public class Address {\n  private String addressLine1;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\npublic class CaseData {\n"
        + "  private Address applicantAddress;\n}\n");

    RetrofitPinnedNames pinned = RetrofitPinnedNames.empty();
    EventComplexTypeResolver resolver =
        resolverFor(src, RetrofitPlannedSynthesis.empty(), pinned);
    FieldModel field = FieldModel.builder()
        .id("applicantAddress").javaName("applicantAddress").fieldType("AddressUK").build();

    assertThat(resolver.resolve(resolver.rootNode(field), "AddressLine1",
        "mandatory", null, null, null, null)).isEmpty();
    assertThat(pinned.isEmpty()).isTrue();
    // The field's own name still resolves — refusing to guess the strategy costs nothing else.
    assertThat(resolve(resolver, field, "addressLine1").getLeafGetter())
        .isEqualTo("getAddressLine1");
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

  @Test
  void unwrapsANonGenericCollectionElementWrapperToItsValueType(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // sscs's collection idiom: the element type is the team's OWN non-generic wrapper holding a single
    // `value`, rather than a generic List<ListValue<X>>. CCD serialises every collection element as
    // {id, value}, so the ListElementCode namespace is rooted at the VALUE type — the chain must walk
    // HearingOutcomeDetails, not the HearingOutcome wrapper (which declares no such members).
    write(src, "m", "HearingOutcomeDetails", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class HearingOutcomeDetails {\n  private String hearingOutcomeId;\n}\n");
    write(src, "m", "HearingOutcome", "package m;\npublic class HearingOutcome {\n"
        + "  private HearingOutcomeDetails value;\n"
        + "  public HearingOutcomeDetails getValue() { return value; }\n}\n");
    write(src, "m", "CaseData", "package m;\nimport java.util.List;\nimport lombok.Data;\n@Data\n"
        + "public class CaseData {\n  private List<HearingOutcome> hearingOutcomes;\n}\n");

    EventComplexTypeResolver resolver = resolverFor(src);
    FieldModel field = FieldModel.builder()
        .id("hearingOutcomes").javaName("hearingOutcomes")
        .fieldType("Collection").fieldTypeParameter("hearingOutcomeDetails").build();

    // The element-typed scope the emitter opens names the VALUE type, so the generated
    // .complex(getter, HearingOutcomeDetails.class) walks the members the definition addresses.
    assertThat(resolver.rootElementType(field).getModelFqn()).isEqualTo("m.HearingOutcomeDetails");
    EventComplexTypeGroup.Member member = resolve(resolver, field, "hearingOutcomeId");
    assertThat(member.getLeafType().getModelFqn()).isEqualTo("m.HearingOutcomeDetails");
    assertThat(member.getLeafGetter()).isEqualTo("getHearingOutcomeId");
  }

  @Test
  void unwrapsAWrapperWhoseValueIsInheritedThroughAGenericSuperclass(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // sscs's second wrapper shape: the wrapper declares no members itself — `value` is inherited from a
    // generic superclass and typed as the type VARIABLE D, bound by the subclass's extends clause. The
    // binding must be substituted to reach SscsDocumentDetails.
    write(src, "m", "SscsDocumentDetails", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class SscsDocumentDetails {\n  private String documentType;\n}\n");
    write(src, "m", "AbstractDocument", "package m;\npublic class AbstractDocument<D> {\n"
        + "  private String id;\n  private D value;\n"
        + "  public D getValue() { return value; }\n}\n");
    write(src, "m", "SscsDocument", "package m;\n"
        + "public class SscsDocument extends AbstractDocument<SscsDocumentDetails> {\n}\n");
    write(src, "m", "CaseData", "package m;\nimport java.util.List;\nimport lombok.Data;\n@Data\n"
        + "public class CaseData {\n  private List<SscsDocument> sscsDocument;\n}\n");

    EventComplexTypeResolver resolver = resolverFor(src);
    FieldModel field = FieldModel.builder()
        .id("sscsDocument").javaName("sscsDocument")
        .fieldType("Collection").fieldTypeParameter("documentDetails").build();

    assertThat(resolver.rootElementType(field).getModelFqn()).isEqualTo("m.SscsDocumentDetails");
    EventComplexTypeGroup.Member member = resolve(resolver, field, "documentType");
    assertThat(member.getLeafGetter()).isEqualTo("getDocumentType");
  }

  @Test
  void doesNotUnwrapAnElementThatMerelyHasAValueMemberAlongsideRealData(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // The guard against over-unwrapping: an element type whose members are `value` PLUS real data is a
    // genuine complex type, not a CCD {id, value} wrapper. Unwrapping it would silently retarget every
    // member chain onto the wrong class, so the walk must stay on the element type itself.
    write(src, "m", "Amount", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class Amount {\n  private String currency;\n}\n");
    write(src, "m", "Payment", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class Payment {\n  private Amount value;\n  private String reference;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport java.util.List;\nimport lombok.Data;\n@Data\n"
        + "public class CaseData {\n  private List<Payment> payments;\n}\n");

    EventComplexTypeResolver resolver = resolverFor(src);
    FieldModel field = FieldModel.builder()
        .id("payments").javaName("payments")
        .fieldType("Collection").fieldTypeParameter("Payment").build();

    assertThat(resolver.rootElementType(field).getModelFqn()).isEqualTo("m.Payment");
    assertThat(resolve(resolver, field, "reference").getLeafGetter()).isEqualTo("getReference");
  }

  @Test
  void doesNotUnwrapAWrapperWhoseValueIsAScalar(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    // A single-`value` type whose value is a String is a value object (sscs's MultiBundleConfig,
    // YesNo), not a complex-element wrapper: there is nothing to walk into, so it must be left alone
    // rather than collapsing to an unresolvable node.
    write(src, "m", "MultiBundleConfig", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class MultiBundleConfig {\n  private String value;\n}\n");
    write(src, "m", "CaseData", "package m;\nimport java.util.List;\nimport lombok.Data;\n@Data\n"
        + "public class CaseData {\n  private List<MultiBundleConfig> multiBundleConfiguration;\n}\n");

    EventComplexTypeResolver resolver = resolverFor(src);
    FieldModel field = FieldModel.builder()
        .id("multiBundleConfiguration").javaName("multiBundleConfiguration")
        .fieldType("Collection").fieldTypeParameter("MultiBundleConfig").build();

    assertThat(resolver.rootElementType(field).getModelFqn()).isEqualTo("m.MultiBundleConfig");
    assertThat(resolve(resolver, field, "value").getLeafGetter()).isEqualTo("getValue");
  }

  @Test
  void rootsAScopeAtTheClassThatDeclaresTheFieldNotTheCaseDataClass(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // Civil's shape: the complex field is declared on a @JsonUnwrapped holder's class, so CaseData has
    // NO getter of its own for it. The scope must open the holder first (getApplicant1DQ) and invoke
    // the field's getter on the holder's class — otherwise the emitted CaseData::getApplicant1DQHearing
    // does not compile.
    write(src, "m", "Hearing", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class Hearing {\n  private String hearingLength;\n}\n");
    write(src, "m/dq", "Applicant1DQ", "package m.dq;\nimport lombok.Data;\nimport m.Hearing;\n@Data\n"
        + "public class Applicant1DQ {\n  private Hearing applicant1DQHearing;\n}\n");
    write(src, "m", "CaseData", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonUnwrapped;\nimport lombok.Data;\n"
        + "import m.dq.Applicant1DQ;\n@Data\npublic class CaseData {\n"
        + "  @JsonUnwrapped private Applicant1DQ applicant1DQ;\n}\n");

    EventComplexTypeResolver resolver = resolverFor(src);
    FieldModel field = FieldModel.builder()
        .id("applicant1DQHearing").javaName("applicant1DQHearing").fieldType("Hearing").build();

    Optional<EventComplexTypeResolver.RootPlacement> placement = resolver.rootPlacement(field);
    assertThat(placement).isPresent();
    assertThat(placement.get().getter()).isEqualTo("getApplicant1DQHearing");
    assertThat(placement.get().hops()).singleElement().satisfies(hop -> {
      assertThat(hop.getGetter()).isEqualTo("getApplicant1DQ");
      assertThat(hop.getTargetType().getModelFqn()).isEqualTo("m.dq.Applicant1DQ");
    });
    // The member walk still binds to the field's own declared type, independent of the placement.
    assertThat(resolve(resolver, field, "hearingLength").getLeafGetter())
        .isEqualTo("getHearingLength");
  }

  @Test
  void refusesTheWholeGroupWhenAnUnwrappedHoldersGetterIsSuppressed(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // The holder is reachable in JSON (Jackson reads the field) but has NO compilable getter, so no
    // method reference can open the scope. The group must refuse to derive and stay a verbatim
    // passthrough — the same refusal RetrofitModelRebinder applies to a clustered PAGE field, so the
    // two placements of one field cannot disagree.
    write(src, "m", "Hearing", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class Hearing {\n  private String hearingLength;\n}\n");
    write(src, "m/dq", "Applicant1DQ", "package m.dq;\nimport lombok.Data;\nimport m.Hearing;\n@Data\n"
        + "public class Applicant1DQ {\n  private Hearing applicant1DQHearing;\n}\n");
    write(src, "m", "CaseData", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonUnwrapped;\n"
        + "import lombok.AccessLevel;\nimport lombok.Data;\nimport lombok.Getter;\n"
        + "import m.dq.Applicant1DQ;\n@Data\npublic class CaseData {\n"
        + "  @JsonUnwrapped @Getter(AccessLevel.NONE) private Applicant1DQ applicant1DQ;\n}\n");

    EventComplexTypeResolver resolver = resolverFor(src);
    FieldModel field = FieldModel.builder()
        .id("applicant1DQHearing").javaName("applicant1DQHearing").fieldType("Hearing").build();

    assertThat(resolver.rootPlacement(field)).isEmpty();
  }

  @Test
  void derivesTheGroupWhenTheSuppressedHoldersGetterWillBeRepaired(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // sscs's shape (writeFinalDecision/otherPartyAttendedQuestions): the same suppressed-getter holder
    // as above, but with the retrofit repair enabled. The patch will delete the
    // @Getter(AccessLevel.NONE) — safe because @JsonUnwrapped already makes the field a visible
    // property off the FIELD, so serialisation is unchanged — and the group therefore derives instead of
    // holding a verbatim passthrough file alive. See RetrofitUnsuppressedGetters.
    write(src, "m", "Hearing", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class Hearing {\n  private String hearingLength;\n}\n");
    write(src, "m/dq", "Applicant1DQ", "package m.dq;\nimport lombok.Data;\nimport m.Hearing;\n@Data\n"
        + "public class Applicant1DQ {\n  private Hearing applicant1DQHearing;\n}\n");
    write(src, "m", "CaseData", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonUnwrapped;\n"
        + "import lombok.AccessLevel;\nimport lombok.Data;\nimport lombok.Getter;\n"
        + "import m.dq.Applicant1DQ;\n@Data\npublic class CaseData {\n"
        + "  @JsonUnwrapped\n"
        + "  @Getter(AccessLevel.NONE)\n"
        + "  private Applicant1DQ applicant1DQ;\n}\n");

    ModelSourceIndex index = ModelSourceIndex.parse(src);
    RetrofitUnsuppressedGetters repairs = RetrofitUnsuppressedGetters.empty();
    index.repairSuppressedGetters(repairs);
    PropertyResolver.Resolution resolution =
        new PropertyResolver(index).resolve(index.byFqn("m.CaseData").orElseThrow());
    EventComplexTypeResolver resolver = new EventComplexTypeResolver(List.of(), PREDEFINED,
        new RetrofitEventComplexTypeGraph(index, resolution,
            index.byFqn("m.CaseData").orElseThrow(), RetrofitPlannedSynthesis.empty(),
            RetrofitPlannedRetypes.empty(), RetrofitPlannedHints.empty(),
            RetrofitPinnedNames.empty()));
    FieldModel field = FieldModel.builder()
        .id("applicant1DQHearing").javaName("applicant1DQHearing").fieldType("Hearing").build();

    Optional<EventComplexTypeResolver.RootPlacement> placement = resolver.rootPlacement(field);
    assertThat(placement).as("the refusal is lifted by the planned repair").isPresent();
    assertThat(placement.get().hops()).singleElement()
        .satisfies(hop -> assertThat(hop.getGetter()).isEqualTo("getApplicant1DQ"));
    // Resolving recorded the reliance, so the patch removes exactly this suppression.
    assertThat(repairs.all()).singleElement()
        .satisfies(u -> assertThat(u.memberName()).isEqualTo("applicant1DQ"));
    assertThat(resolve(resolver, field, "hearingLength").getLeafGetter())
        .isEqualTo("getHearingLength");
  }

  @Test
  void keepsTheDefinitionDerivedGetterForAFieldTheModelDoesNotDeclare(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    // A definition-only complex field: the patch synthesises it onto the root class, so the graph has
    // no binding and the linker keeps its own CCD-id-derived getter, rooted directly on CaseData.
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class CaseData {\n  private String unrelated;\n}\n");

    EventComplexTypeResolver resolver = resolverFor(src);
    FieldModel field = FieldModel.builder()
        .id("synthesisedThing").javaName("synthesisedThing").fieldType("Thing").build();

    Optional<EventComplexTypeResolver.RootPlacement> placement = resolver.rootPlacement(field);
    assertThat(placement).isPresent();
    assertThat(placement.get().getter()).isEqualTo("getSynthesisedThing");
    assertThat(placement.get().hops()).isEmpty();
  }
}
