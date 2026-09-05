package uk.gov.hmcts.example.callback;

/**
 * A fixed list the team DOES model as an enum, in a package of its own, but which no field declares:
 * every column typed by it is a String carrying the list ID as a typeParameterOverride (sscs's
 * ScannedDocumentDetails.type). Reflection reaches an enum only from a field's declared type, so nothing
 * generated this list's rows until the patch named the enum with @CCD(typeParameterClass).
 */
public enum ScannedDocumentType {
  FORM,
  COVERSHEET
}
