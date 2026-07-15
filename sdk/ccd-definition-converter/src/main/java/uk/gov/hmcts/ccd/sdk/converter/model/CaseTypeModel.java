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
   * Whether every input {@code CaseRoles} row carries a {@code JurisdictionID}: when true the
   * emitter calls {@code builder.emitCaseRoleJurisdiction()} so the generator stamps the column.
   * When only some rows carry it, this is false and the column is grafted via passthrough instead.
   */
  boolean emitCaseRoleJurisdiction;

  /**
   * AuthorisationComplexType grants emitted via {@code builder.grantComplexType(...)}.
   */
  List<ComplexTypeAuthModel> complexTypeAuthorisations;

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
