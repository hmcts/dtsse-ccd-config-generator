package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * An opaque, stable reference to a source document held by the consuming service.
 *
 * <p>The SDK never interprets a reference. The {@link DocumentResolver} whose
 * {@link DocumentResolver#provider()} matches {@code provider} turns it into content. A reference
 * must not be a URL and must never carry authorisation material.
 *
 * @param provider the name of the resolver that can resolve this reference
 * @param id the provider-scoped identifier of the document
 */
public record DocumentReference(String provider, String id) {

  public DocumentReference {
    Validate.requireNonBlank(provider, "DocumentReference.provider");
    Validate.requireNonBlank(id, "DocumentReference.id");
  }
}
