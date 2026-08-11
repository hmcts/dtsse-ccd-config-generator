package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * A case event and everything the config emitter needs to register it: state targeting,
 * display metadata, grants and wizard pages.
 *
 * <p>Per-environment variants: when the same event ID appears in the base definition and in
 * overlay files (or only in an overlay), the linker produces one EventModel per variant with
 * {@link #overlayCondition} set; the config emitter guards each variant's registration with
 * the generated EnvironmentFlags check.
 */
@Value
@Builder
public class EventModel {

  /** The CaseEvent ID, e.g. "startAppeal". */
  String id;

  /** The generated Java method-name-safe form of the ID. */
  String javaName;

  String name;
  String description;

  /** Parsed PreConditionState(s): empty = initial event, ["*"] = all states. */
  List<String> preStates;

  /** PostConditionState; "*" means no state change, null means the column was absent. */
  String postState;

  /**
   * Whether the input's CaseEvent row carried no {@code PostConditionState} at all — emitted via
   * {@code EventBuilder.postStateFromCallback()} so the generator omits the column too.
   *
   * <p>An absent column is a third runtime behaviour, distinct from both a concrete state and
   * {@code *}: the state the about-to-submit callback returned is the only one applied. Without this
   * flag the generator flattens it to {@code *} (or to the pre-state), writing a column the
   * hand-written definition deliberately omitted.
   */
  boolean postStateFromCallback;

  Integer displayOrder;

  /** EventEnablingCondition / show condition on the event. */
  String showCondition;

  Boolean showSummary;
  Boolean showEventNotes;

  /**
   * CaseEvent {@code SignificantEvent} flag — emitted via {@code EventBuilder.significant()}.
   */
  Boolean significant;

  /**
   * CaseEvent {@code CanSaveDraft} flag — emitted via {@code EventBuilder.canSaveDraft()}. Valid
   * only on create events (no pre-state); the definition-store importer rejects it otherwise.
   */
  Boolean canSaveDraft;

  String endButtonLabel;
  Boolean publish;
  String publishAs;
  Integer ttlIncrement;

  /** AuthorisationCaseEvent grants: role id to CRUD string. */
  Map<String, String> grants;

  List<PageModel> pages;

  /**
   * Environment predicate when this event registration comes from an overlay file;
   * null for base rows.
   */
  OverlayCondition overlayCondition;

  /**
   * The overlay suffix ("prod", "WA-nonprod", …) behind {@link #overlayCondition}, or null.
   */
  String overlaySuffix;
}
