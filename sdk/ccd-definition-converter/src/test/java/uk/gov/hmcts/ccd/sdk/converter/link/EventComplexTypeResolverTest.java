package uk.gov.hmcts.ccd.sdk.converter.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.EventComplexTypeGroup;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;

/**
 * Unit tests for {@link EventComplexTypeResolver}: walking a {@code CaseEventToComplexTypes} row's
 * dotted {@code ListElementCode} into a typed getter chain, honouring the SDK's exact member-naming
 * math (generated-type {@code javaName}, predefined-type {@code @JsonProperty}/field-name with the
 * {@code StringUtils.capitalize} getter rule), and falling back cleanly for anything it cannot
 * express (unknown member, collection hop, non-complex intermediate).
 */
class EventComplexTypeResolverTest {

  private static final Map<String, String> PREDEFINED = SdkPredefinedTypes.all();

  /** A generated complex type "Party" with a scalar member and a nested "Contact" member. */
  private static ComplexTypeModel party() {
    return ComplexTypeModel.builder()
        .id("Party")
        .javaClassName("Party")
        .members(List.of(
            FieldModel.builder().id("firstName").javaName("firstName").fieldType("Text").build(),
            // Member CCD id differs from the Java member name — the getter must use the javaName.
            FieldModel.builder().id("PartyID").javaName("partyId").fieldType("Text").build(),
            FieldModel.builder().id("contact").javaName("contact").fieldType("Contact").build(),
            // A collection member — the resolver must not descend into it.
            FieldModel.builder().id("children").javaName("children")
                .fieldType("Collection").fieldTypeParameter("Party").build()))
        .depth(0)
        .build();
  }

  private static ComplexTypeModel contact() {
    return ComplexTypeModel.builder()
        .id("Contact")
        .javaClassName("Contact")
        .members(List.of(
            FieldModel.builder().id("email").javaName("email").fieldType("Email").build()))
        .depth(0)
        .build();
  }

  private EventComplexTypeResolver resolver() {
    return new EventComplexTypeResolver(List.of(party(), contact()), PREDEFINED);
  }

  @Test
  void resolvesADirectGeneratedMemberToItsTypedGetter() {
    Optional<EventComplexTypeGroup.Member> member = resolver().resolve(
        "Party", "firstName", "mandatory", null, "First name", null, null);

    assertThat(member).isPresent();
    assertThat(member.get().getHops()).isEmpty();
    assertThat(member.get().getLeafType().getSimpleName()).isEqualTo("Party");
    assertThat(member.get().getLeafGetter()).isEqualTo("getFirstName");
    assertThat(member.get().getContextMethod()).isEqualTo("mandatory");
    assertThat(member.get().getEventLabel()).isEqualTo("First name");
  }

  @Test
  void usesTheJavaNameNotTheCcdIdForAGeneratedMemberGetter() {
    // The member's CCD id is "PartyID" but its Java member is "partyId"; the getter must be
    // getPartyId (deriving from the javaName), so the SDK re-emits the "PartyID" ListElementCode.
    Optional<EventComplexTypeGroup.Member> member = resolver().resolve(
        "Party", "PartyID", "optional", null, null, null, null);

    assertThat(member).isPresent();
    assertThat(member.get().getLeafGetter()).isEqualTo("getPartyId");
  }

  @Test
  void resolvesAMultiDotNestingThroughAGeneratedType() {
    Optional<EventComplexTypeGroup.Member> member = resolver().resolve(
        "Party", "contact.email", "optional", "contact.email=\"*\"", null, null, "2");

    assertThat(member).isPresent();
    assertThat(member.get().getHops()).singleElement().satisfies(hop -> {
      assertThat(hop.getDeclaringType().getSimpleName()).isEqualTo("Party");
      assertThat(hop.getGetter()).isEqualTo("getContact");
    });
    assertThat(member.get().getLeafType().getSimpleName()).isEqualTo("Contact");
    assertThat(member.get().getLeafGetter()).isEqualTo("getEmail");
    assertThat(member.get().getShowCondition()).isEqualTo("contact.email=\"*\"");
    assertThat(member.get().getPageId()).isEqualTo("2");
  }

  @Test
  void resolvesThroughAPredefinedTypeHopHonouringJsonPropertyAndCapitalisationQuirks() {
    // ChangeOrganisationRequest.OrganisationToAdd (a predefined Organisation) . OrganisationID:
    // the input segment "OrganisationID" must match Organisation's @JsonProperty("OrganisationID")
    // field organisationId, whose getter is getOrganisationId (capitalize of the Java field name),
    // NOT getOrganisationID.
    FieldModel field = FieldModel.builder()
        .id("changeOrganisationRequestField").javaName("changeOrganisationRequestField")
        .fieldType("ChangeOrganisationRequest").build();
    EventComplexTypeResolver resolver =
        new EventComplexTypeResolver(List.of(), PREDEFINED);

    assertThat(resolver.rootTypeId(field)).isEqualTo("ChangeOrganisationRequest");
    Optional<EventComplexTypeGroup.Member> member = resolver.resolve(
        "ChangeOrganisationRequest", "OrganisationToAdd.OrganisationID",
        "optional", null, null, null, null);

    assertThat(member).isPresent();
    assertThat(member.get().getHops()).singleElement().satisfies(hop ->
        assertThat(hop.getGetter()).isEqualTo("getOrganisationToAdd"));
    assertThat(member.get().getLeafType().getPredefinedFqn())
        .isEqualTo("uk.gov.hmcts.ccd.sdk.type.Organisation");
    assertThat(member.get().getLeafGetter()).isEqualTo("getOrganisationId");
  }

  @Test
  void resolvesAFlatPredefinedMemberWhoseJsonPropertyMatchesTheSegment() {
    Optional<EventComplexTypeGroup.Member> member = resolver().resolve(
        "ChangeOrganisationRequest", "CaseRoleId", "optional", null, null, null, null);

    assertThat(member).isPresent();
    assertThat(member.get().getHops()).isEmpty();
    assertThat(member.get().getLeafGetter()).isEqualTo("getCaseRoleId");
  }

  @Test
  void carriesTheGeneratedMembersDeclaredHintForTheLeakGuard() {
    ComplexTypeModel withHint = ComplexTypeModel.builder()
        .id("Party").javaClassName("Party")
        .members(List.of(FieldModel.builder()
            .id("event").javaName("event").fieldType("Text").hint("A few words").build()))
        .depth(0).build();
    EventComplexTypeResolver resolver =
        new EventComplexTypeResolver(List.of(withHint), PREDEFINED);

    Optional<EventComplexTypeGroup.Member> member = resolver.resolve(
        "Party", "event", "optional", null, null, null, null);

    assertThat(member).isPresent();
    assertThat(member.get().getDeclaredHint()).isEqualTo("A few words");
  }

  @Test
  void fallsBackWhenAMemberIsUnknown() {
    assertThat(resolver().resolve("Party", "noSuchMember", "optional", null, null, null, null))
        .isEmpty();
  }

  @Test
  void fallsBackWhenAnIntermediateSegmentIsScalar() {
    // firstName is a scalar Text member, so "firstName.x" cannot descend into it.
    assertThat(resolver().resolve("Party", "firstName.x", "optional", null, null, null, null))
        .isEmpty();
  }

  @Test
  void descendsThroughACollectionMemberViaItsElementType() {
    // "children" is a Collection<Party> member; the resolver now descends into the element type via
    // the two-arg element-typed .complex(getter, Party.class) scope, so children.firstName resolves
    // and the hop records the element type ref for the emitter.
    Optional<EventComplexTypeGroup.Member> member = resolver().resolve(
        "Party", "children.firstName", "mandatory", null, null, null, null);

    assertThat(member).isPresent();
    assertThat(member.get().getHops()).singleElement().satisfies(hop -> {
      assertThat(hop.getDeclaringType().getSimpleName()).isEqualTo("Party");
      assertThat(hop.getGetter()).isEqualTo("getChildren");
      // The element-type ref drives the emitter's .complex(getter, Element.class) overload.
      assertThat(hop.getElementType().getSimpleName()).isEqualTo("Party");
    });
    assertThat(member.get().getLeafType().getSimpleName()).isEqualTo("Party");
    assertThat(member.get().getLeafGetter()).isEqualTo("getFirstName");
  }

  @Test
  void descendsThroughNestedCollectionInCollection() {
    // A collection-in-collection path: children (Collection<Party>) . children (Collection<Party>)
    // . firstName. Both hops descend via the element-typed scope and both carry an element-type ref.
    Optional<EventComplexTypeGroup.Member> member = resolver().resolve(
        "Party", "children.children.firstName", "optional", null, null, null, null);

    assertThat(member).isPresent();
    assertThat(member.get().getHops()).hasSize(2);
    assertThat(member.get().getHops()).allSatisfy(hop -> {
      assertThat(hop.getGetter()).isEqualTo("getChildren");
      assertThat(hop.getElementType().getSimpleName()).isEqualTo("Party");
    });
    assertThat(member.get().getLeafGetter()).isEqualTo("getFirstName");
  }

  @Test
  void collectionRootFieldResolvesToItsElementType() {
    // A Collection-typed CaseField's getter is List<ListValue<Party>>; rootTypeId now returns the
    // element type and rootElementType supplies the ref the emitter opens the element scope with.
    FieldModel collectionField = FieldModel.builder()
        .id("parties").javaName("parties")
        .fieldType("Collection").fieldTypeParameter("Party").build();
    assertThat(resolver().rootTypeId(collectionField)).isEqualTo("Party");
    assertThat(resolver().rootElementType(collectionField).getSimpleName()).isEqualTo("Party");

    // A direct member of the collection element resolves with no hops (a root-level element member).
    Optional<EventComplexTypeGroup.Member> member = resolver().resolve(
        "Party", "firstName", "mandatory", null, null, null, null);
    assertThat(member).isPresent();
    assertThat(member.get().getHops()).isEmpty();
    assertThat(member.get().getLeafGetter()).isEqualTo("getFirstName");
  }

  @Test
  void scalarComplexRootHasNoElementType() {
    // A scalar complex root uses the one-arg .complex(getter); rootElementType is null so the emitter
    // does not open the element-typed scope.
    FieldModel complexField = FieldModel.builder()
        .id("party").javaName("party").fieldType("Party").build();
    assertThat(resolver().rootElementType(complexField)).isNull();
  }

  @Test
  void fallsBackWhenACollectionElementTypeIsUnknown() {
    // A Collection whose element type is neither generated nor predefined cannot be walked.
    FieldModel collectionField = FieldModel.builder()
        .id("things").javaName("things")
        .fieldType("Collection").fieldTypeParameter("NoSuchType").build();
    assertThat(resolver().rootTypeId(collectionField)).isNull();
    assertThat(resolver().rootElementType(collectionField)).isNull();
  }

  @Test
  void directComplexRootFieldIsWalkable() {
    FieldModel complexField = FieldModel.builder()
        .id("party").javaName("party").fieldType("Party").build();
    assertThat(resolver().rootTypeId(complexField)).isEqualTo("Party");
  }

  @Test
  void fallsBackWhenTheRootTypeIsNeitherGeneratedNorPredefined() {
    assertThat(resolver().resolve("NoSuchType", "firstName", "optional", null, null, null, null))
        .isEmpty();
  }
}
