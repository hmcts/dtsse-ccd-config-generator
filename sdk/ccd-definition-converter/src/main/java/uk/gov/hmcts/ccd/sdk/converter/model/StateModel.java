package uk.gov.hmcts.ccd.sdk.converter.model;

import lombok.Builder;
import lombok.Value;

/**
 * A case state, destined for a constant on the generated State enum.
 *
 * <p>The generated enum constant name must literally equal the state ID — the SDK's
 * StateGenerator looks enum constants up by {@code toString()} via reflection, so IDs that
 * are not legal Java identifiers cannot be represented and become gaps.
 */
@Value
@Builder
public class StateModel {

  /** The state ID, e.g. "Open". Also the enum constant name. */
  String id;

  /** State display name (State sheet Name column) — emitted as @CCD(label). */
  String name;

  /** TitleDisplay markup, if any — emitted as @CCD(hint). */
  String titleDisplay;

  String description;
}
