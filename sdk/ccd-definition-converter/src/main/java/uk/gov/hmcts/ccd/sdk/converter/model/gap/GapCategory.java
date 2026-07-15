package uk.gov.hmcts.ccd.sdk.converter.model.gap;

/** Why the converter could not express something as config-generator Java code. */
public enum GapCategory {
  /** The whole sheet has no config-generator equivalent (SearchAlias, UserProfile, AccessType…). */
  UNSUPPORTED_SHEET,
  /** The column is not emitted by any generator (CallbackGetCaseUrl, NullifyByDefault…). */
  UNSUPPORTED_COLUMN,
  /** The column is supported but this value cannot be reproduced (e.g. non-integer Min/Max). */
  UNSUPPORTED_VALUE,
  /** A per-environment overlay row on a sheet with no programmatic API. */
  OVERLAY_NOT_EXPRESSIBLE,
  /** An ID was sanitised to form a legal Java identifier. */
  IDENTIFIER_SANITISED,
  /** AuthorisationCaseField rows that no access-class assignment reproduces exactly. */
  AUTH_NOT_DERIVABLE
}
