package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetRow;

/**
 * The fully linked semantic model of one case type — everything the emitters need to
 * generate case data classes, enums and config classes.
 *
 * <p>This is the contract between the linker and the emitters. Sheets held as raw
 * {@link SheetRow} lists (search criteria, search party, challenge questions,
 * role-to-access-profiles, categories) map closely enough onto their ConfigBuilder
 * sub-builders that the config emitter consumes the rows directly.
 */
@Value
@Builder(toBuilder = true)
public class CaseTypeModel {

  String caseTypeId;
  String caseTypeName;
  String caseTypeDescription;

  String jurisdictionId;
  String jurisdictionName;
  String jurisdictionDescription;

  List<StateModel> states;
  List<RoleModel> roles;

  /**
   * The jurisdiction service-notice banner, or null when the input carries no Banner sheet.
   */
  BannerModel banner;

  /**
   * CaseType {@code EnableForDeletion} flag — emitted via {@code builder.enableForDeletion()}.
   */
  boolean enableForDeletion;

  /**
   * Jurisdiction {@code Shuttered} flag — emitted via {@code builder.jurisdictionShuttered()}.
   */
  boolean jurisdictionShuttered;

  /**
   * CaseType {@code PrintableDocumentsUrl} column — emitted via
   * {@code builder.printableDocumentsUrl(url)}, or null when the input row omits it.
   */
  String printableDocumentsUrl;

  /**
   * Whether every input {@code CaseRoles} row carries a {@code JurisdictionID}: when true the
   * emitter calls {@code builder.emitCaseRoleJurisdiction()} so the generator stamps the column.
   * When only some rows carry it, this is false and the column is grafted via passthrough instead.
   */
  boolean emitCaseRoleJurisdiction;

  /**
   * AuthorisationComplexType grants emitted via {@code builder.grantComplexType(...)}.
   */
  List<ComplexTypeAuthModel> complexTypeAuthorisations;

  /**
   * Delegating no-arg getters the retrofit patch synthesises on the root case-data class so a
   * {@code grantComplexType} can reference a complex field reached only through a
   * {@code @JsonUnwrapped} member by a real {@code CaseData::getX} method reference (never a multi-hop
   * lambda, which the SDK cannot resolve — see {@link ComplexTypeAuthGetter}). Keyed by CCD field id.
   * When a grant's field id is present, the config emitter references the delegating getter name
   * rather than the (nonexistent) direct getter; empty in generate mode and for fields with a direct
   * getter.
   */
  @Builder.Default
  Map<String, ComplexTypeAuthGetter> complexTypeAuthGetters = Map.of();

  /** CaseField rows that become members of the generated CaseData class. */
  List<FieldModel> caseFields;

  List<ComplexTypeModel> complexTypes;
  List<FixedListModel> fixedLists;
  List<EventModel> events;
  List<TabModel> tabs;

  List<SearchFieldModel> searchInputFields;
  List<SearchFieldModel> searchResultFields;
  List<SearchFieldModel> workBasketInputFields;
  List<SearchFieldModel> workBasketResultFields;
  List<SearchFieldModel> searchCasesResultFields;

  /** AuthorisationCaseState rows: state grants for ConfigBuilder.grant(state, perms, roles). */
  List<SheetRow> stateAuthorisations;

  List<AccessClassModel> accessClasses;

  /**
   * A human-readable note recording the composition access-emission outcome for the converter's
   * generate/retrofit reports: the total class count broken into groups/atoms/dedicated fallbacks,
   * the per-field {@code @CCD(access)} array distribution (avg/max), and the mined-group table (see
   * {@code AccessClassComputer}).
   */
  String accessSummaryNote;

  List<SheetRow> searchCriteria;
  List<SheetRow> searchParties;
  List<SheetRow> challengeQuestions;
  List<SheetRow> roleToAccessProfiles;
  List<SheetRow> categories;

  /** Raw JSON to merge into the generated output after generation. */
  List<PassthroughSheet> passthroughSheets;

  /**
   * Derivable {@code CaseEventToComplexTypes} groups, keyed {@code eventId + '' + caseFieldId}.
   * A group is present only when every one of the field's per-member event-override rows resolved to
   * a typed getter chain and the field is placed as {@code COMPLEX} on the event (see
   * {@code EventComplexTypeResolver} / {@code DefaultDefinitionLinker.buildEventToComplexTypesPassthrough}).
   * The config emitter consults this when placing a COMPLEX field: a keyed group is emitted as
   * {@code .complex(getter).<ctx>(member)…} builder chains rather than an empty {@code .complex(getter)}
   * block, and its rows' non-derivable columns ({@code ID}, {@code FieldDisplayOrder}, exotic tail)
   * are grafted over the generated rows. Groups that did not resolve stay whole-row passthrough.
   */
  @Builder.Default
  Map<String, EventComplexTypeGroup> eventComplexTypeGroups = Map.of();

  /**
   * Flat CCD field ID (e.g. {@code applicant1FirstName}) to the reference the config emitter
   * must use when placing that field on an event/tab/search: the unwrapped complex parent plus
   * the member getter. Populated only for fields folded into a {@code @JsonUnwrapped} cluster;
   * fields absent from this map are ordinary {@code CaseData::getX} references.
   */
  Map<String, ClusteredFieldRef> clusteredFieldRefs;

  /**
   * CCD field IDs the config emitter must NOT place via a typed method reference because the team's
   * model exposes no resolvable getter for them (retrofit only, finding Bug4): a field reached
   * through a {@code @JsonUnwrapped} parent whose Lombok getter is suppressed with
   * {@code @Getter(AccessLevel.NONE)} and that has no correctly-named hand-written accessor (SSCS's
   * {@code finalDecisionCaseData}/{@code pipSscsCaseData}/{@code sscsDeprecatedFields} families).
   * Emitting {@code CaseData::getParent} for such a field is an "invalid method reference" compile
   * error, and the SDK exposes no public string-id overload for event fields, so the placement is
   * skipped and the row's per-field metadata is carried by the CaseEventToFields column passthrough.
   * Empty in generate mode and for models whose unwrapped parents all have getters.
   */
  @Builder.Default
  java.util.Set<String> unplaceableFieldIds = java.util.Set.of();
}
