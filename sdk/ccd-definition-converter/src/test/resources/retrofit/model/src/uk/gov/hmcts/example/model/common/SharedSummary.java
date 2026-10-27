package uk.gov.hmcts.example.model.common;

/**
 * One model class backing SEVERAL definition complex types that have no class of their own — sscs's
 * shape, where ten {@code dwp*DocumentCT} types are all declared as a single
 * {@code DwpResponseDocument}. A class can carry only one {@code @ComplexType(name)}, so the ID pin
 * refuses them all; the retrofit retype re-declares each FIELD as its own generated companion instead.
 */
public class SharedSummary {

  private String summary;
}
