package uk.gov.hmcts.ccd.sdk.converter.model.gap;

import lombok.Builder;
import lombok.Value;

/**
 * One thing in the input definition the converter could not express as config-generator
 * Java code, and what was done about it.
 *
 * <p>The invariant enforced across the converter: nothing is silently dropped. Every input
 * row and column is expressed in code, passed through as raw JSON, or recorded here with
 * {@link GapAction#OMITTED_FAIL} (which fails the conversion unless {@code --allow-gaps}).
 */
@Value
@Builder
public class GapEntry {

  /** The definition sheet the finding relates to, e.g. "CaseField". */
  String sheet;

  /** A human-readable row key, e.g. "applicantName" or "startAppeal/caseworker". */
  String rowKey;

  /** The column involved, or null when the whole row/sheet is affected. */
  String column;

  /** The offending value, where useful for diagnosis. */
  String value;

  GapCategory category;

  GapAction action;

  /** Free-text explanation aimed at the migrating team. */
  String detail;
}
