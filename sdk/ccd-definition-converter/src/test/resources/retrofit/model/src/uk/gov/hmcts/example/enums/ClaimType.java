package uk.gov.hmcts.example.enums;

/** A plain enum: infers to FixedRadioList (no @ComplexType), and used as a State-like list. */
public enum ClaimType {
  PERSONAL_INJURY,
  CONTRACT,
  DEBT
}
