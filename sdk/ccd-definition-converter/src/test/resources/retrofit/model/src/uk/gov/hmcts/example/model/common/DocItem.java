package uk.gov.hmcts.example.model.common;

/**
 * A CONCRETE value-bearing wrapper (no type parameter), like SSCS's {@code Hearing} or ET's
 * {@code DocumentTypeItem}: {@code List<DocItem>} does NOT descend under the SDK's
 * {@code hasGenerics()} rule, so it mis-resolves to {@code DocItem} and needs
 * {@code @CCD(typeParameterOverride = ...)} (decision 8).
 */
public class DocItem {

  private String id;

  private Document value;
}
