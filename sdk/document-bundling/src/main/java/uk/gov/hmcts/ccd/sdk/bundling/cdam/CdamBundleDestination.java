package uk.gov.hmcts.ccd.sdk.bundling.cdam;

import feign.FeignException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDestination;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.StoredBundle;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.ccd.document.am.model.CaseDocumentsMetadata;
import uk.gov.hmcts.reform.ccd.document.am.model.Document;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentHashToken;
import uk.gov.hmcts.reform.ccd.document.am.model.DocumentUploadRequest;
import uk.gov.hmcts.reform.ccd.document.am.model.UploadResponse;
import uk.gov.hmcts.reform.ccd.document.am.util.InMemoryMultipartFile;

/**
 * The invariant production destination: publishes the finished bundle to CDAM, the centralised
 * document blob store, through {@link CaseDocumentClientApi#uploadDocuments}.
 *
 * <p>Publication is atomic — this destination returns only after CDAM has accepted the upload
 * and reported the stored document's links, so a failed job never replaces the last successful
 * bundle. The upload classification comes from explicit {@link CdamUploadSettings}; it is never
 * defaulted. Authentication uses the consuming service's system user via
 * {@link BundlingAuthenticationProvider}, never an end-user token.
 *
 * <p>An upload alone is not durable: CDAM disposes of a document that is never associated with a
 * case once its time-to-live expires. When {@link CdamUploadSettings#attachToCase()} is set, this
 * destination completes that association itself — it calls CDAM's
 * {@code /cases/documents/attachToCase} with the fresh document's hash token and the case
 * reference from the {@link BundleExecutionContext}, the shape required for decentralised
 * submit-handler events (see {@link CdamUploadSettings} for the permission model and for when the
 * consumer's own case-data submission is the right shape instead). Attach-at-upload requires the
 * context to carry a well-formed (16-digit) case reference, checked before anything is uploaded
 * so a reference CDAM must reject never leaves an orphaned document behind. If the attach call
 * itself fails after the upload, the uploaded document is left unattached and is disposed of by
 * CDAM when its TTL expires, so nothing was published.
 *
 * <p>Attach semantics are at-least-once, not exactly-once. Once the attach call succeeds the
 * document is permanently associated with the case — it will never be TTL-disposed — but the
 * caller's own record of the bundle (its case data or job row) commits separately. A crash
 * between the successful attach and that record, or a lease-reclaimed job rendering twice, can
 * therefore leave an attached document that nothing references: an invisible duplicate on the
 * case's document store, harmless but real. Consumers' case data only ever references the
 * recorded document; no practical mechanism exists to make the attach and the record atomic
 * across the two services.
 *
 * <p>Known limitation: the artifact is double-buffered on the heap for the upload — once by
 * {@code readAllBytes} into the multipart file and once by the Feign form encoder — because the
 * client's {@code DocumentUploadRequest} API takes fully materialised {@code MultipartFile}s.
 * This matches what the current stitching service does today; the long-term fix is a streaming
 * upload path in {@code ccd-case-document-am-client}, not a restructuring here.
 */
public final class CdamBundleDestination implements BundleDestination {

  private static final String MULTIPART_FIELD_NAME = "files";

  /** CDAM validates attachToCase's caseId as exactly 16 digits; enforced before any upload. */
  private static final Pattern CASE_REFERENCE_FORMAT = Pattern.compile("[0-9]{16}");

  private final CaseDocumentClientApi caseDocumentClientApi;
  private final BundlingAuthenticationProvider authenticationProvider;
  private final CdamUploadSettings settings;

  /**
   * Creates the destination.
   *
   * @param caseDocumentClientApi the CDAM client
   * @param authenticationProvider the system-user authentication port
   * @param settings the explicit upload coordinates, including classification
   */
  public CdamBundleDestination(
      CaseDocumentClientApi caseDocumentClientApi,
      BundlingAuthenticationProvider authenticationProvider,
      CdamUploadSettings settings) {
    if (caseDocumentClientApi == null) {
      throw new IllegalArgumentException("CdamBundleDestination.caseDocumentClientApi must be provided");
    }
    if (authenticationProvider == null) {
      throw new IllegalArgumentException("CdamBundleDestination.authenticationProvider must be provided");
    }
    if (settings == null) {
      throw new IllegalArgumentException("CdamBundleDestination.settings must be provided");
    }
    this.caseDocumentClientApi = caseDocumentClientApi;
    this.authenticationProvider = authenticationProvider;
    this.settings = settings;
  }

  @Override
  public StoredBundle store(BundleArtifact artifact, BundleExecutionContext context) {
    byte[] content = readArtifact(artifact);
    if (content.length == 0) {
      throw new CdamUploadException(
          "Bundle artifact '" + artifact.fileName()
              + "' is empty; refusing to publish a zero-byte bundle to CDAM");
    }
    // Checked before the upload: attach-at-upload with a missing or malformed case reference
    // could only produce an orphaned document that CDAM disposes of at TTL expiry (afresh on
    // every retry), so nothing is sent at all.
    String caseReference = settings.attachToCase() ? requireCaseReference(artifact, context) : null;
    DocumentUploadRequest request = new DocumentUploadRequest(
        settings.classification().toString(),
        settings.caseTypeId(),
        settings.jurisdictionId(),
        List.of(new InMemoryMultipartFile(
            MULTIPART_FIELD_NAME, artifact.fileName(), artifact.mediaType(), content)));

    Tokens tokens = acquireTokens(artifact);
    UploadResponse response = upload(artifact, request, tokens);
    Document document = firstDocument(artifact, response);
    String selfLink = selfLink(artifact, document);
    Optional<String> hashToken =
        Optional.ofNullable(document.hashToken).filter(token -> !token.isBlank());

    if (caseReference != null) {
      attachToCase(artifact, selfLink, hashToken, caseReference, tokens);
    }

    return new StoredBundle(
        selfLink,
        binaryLink(artifact, document),
        artifact.fileName(),
        artifact.mediaType(),
        artifact.size(),
        artifact.sha256(),
        hashToken);
  }

  private static String requireCaseReference(BundleArtifact artifact, BundleExecutionContext context) {
    String caseReference = context.caseReference().orElseThrow(() -> new CdamUploadException(
        "CdamUploadSettings.attachToCase is set but the BundleExecutionContext for bundle '"
            + artifact.fileName() + "' carries no caseReference, so the uploaded document could "
            + "never be attached to a case and would be disposed of by CDAM at TTL expiry. Set "
            + "BundleExecutionContext.caseReference, or turn attach-at-upload off and attach the "
            + "stored bundle through your own case-data submission; CDAM was not called", true));
    if (!CASE_REFERENCE_FORMAT.matcher(caseReference).matches()) {
      throw new CdamUploadException(
          "CdamUploadSettings.attachToCase is set but the BundleExecutionContext for bundle '"
              + artifact.fileName() + "' carries caseReference '" + caseReference + "' ("
              + caseReference.length() + " characters), which is not the 16-digit case reference "
              + "CDAM's attachToCase accepts — the attach would be rejected after the upload, "
              + "orphaning the document. Pass the CCD case reference as exactly 16 digits; "
              + "CDAM was not called", true);
    }
    return caseReference;
  }

  private void attachToCase(BundleArtifact artifact, String selfLink, Optional<String> hashToken,
      String caseReference, Tokens tokens) {
    var metadata = new CaseDocumentsMetadata(
        caseReference,
        settings.caseTypeId(),
        settings.jurisdictionId(),
        List.of(new DocumentHashToken(documentId(artifact, selfLink), hashToken.orElse(null))));
    try {
      caseDocumentClientApi.patchDocument(tokens.systemUserToken(), tokens.serviceToken(), metadata);
    } catch (FeignException e) {
      // Deliberately not chained: FeignException messages embed the raw response body. The
      // uploaded document is left unattached and CDAM disposes of it when its TTL expires.
      throw new CdamUploadException(
          "CDAM accepted the upload of bundle '" + artifact.fileName() + "' but attaching it to "
              + "case " + caseReference + " failed "
              + (e.status() > 0 ? "with HTTP status " + e.status() : "without an HTTP response")
              + "; the unattached document will be disposed of by CDAM at TTL expiry, so nothing "
              + "was published. A 403 usually means this service's S2S identity lacks CDAM "
              + "ATTACH permission for " + settings.jurisdictionId() + "/" + settings.caseTypeId(),
          isClientError(e));
    }
  }

  private static boolean isClientError(FeignException e) {
    // A 4xx is a permanent rejection — retrying with the same configuration and request cannot
    // succeed and would only orphan a fresh upload per attempt. 5xx and no-response are transient.
    return e.status() >= 400 && e.status() < 500;
  }

  private static String documentId(BundleArtifact artifact, String selfLink) {
    int lastSlash = selfLink.lastIndexOf('/');
    String documentId = lastSlash >= 0 ? selfLink.substring(lastSlash + 1) : "";
    if (documentId.isBlank()) {
      throw new CdamUploadException(
          "CDAM upload of bundle '" + artifact.fileName() + "' returned a self link without a "
              + "document id, so the document cannot be attached to its case");
    }
    return documentId;
  }

  private byte[] readArtifact(BundleArtifact artifact) {
    try (InputStream in = artifact.open()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new CdamUploadException(
          "Could not read bundle artifact '" + artifact.fileName() + "' for CDAM upload", e);
    }
  }

  private Tokens acquireTokens(BundleArtifact artifact) {
    // Tokens are acquired before the CDAM calls so an IDAM/S2S failure is reported as a token
    // acquisition failure, never blamed on CDAM. The same tokens serve the upload and, when
    // configured, the immediately following attach.
    try {
      return new Tokens(authenticationProvider.systemUserToken(), authenticationProvider.serviceToken());
    } catch (RuntimeException e) {
      // Not chained: token provider exceptions may embed request URLs or response bodies.
      throw new CdamUploadException(
          "Token acquisition failed (" + e.getClass().getSimpleName() + ") before the CDAM upload of bundle '"
              + artifact.fileName() + "'; CDAM was not called");
    }
  }

  private UploadResponse upload(BundleArtifact artifact, DocumentUploadRequest request, Tokens tokens) {
    try {
      return caseDocumentClientApi.uploadDocuments(tokens.systemUserToken(), tokens.serviceToken(), request);
    } catch (FeignException e) {
      // Deliberately not chained: FeignException messages embed the raw response body.
      throw new CdamUploadException(
          "CDAM upload of bundle '" + artifact.fileName() + "' failed "
              + (e.status() > 0 ? "with HTTP status " + e.status() : "without an HTTP response"),
          isClientError(e));
    }
  }

  private Document firstDocument(BundleArtifact artifact, UploadResponse response) {
    if (response == null || response.getDocuments() == null || response.getDocuments().isEmpty()) {
      throw new CdamUploadException(
          "CDAM upload of bundle '" + artifact.fileName()
              + "' returned no document metadata; the bundle cannot be published");
    }
    return response.getDocuments().get(0);
  }

  private String selfLink(BundleArtifact artifact, Document document) {
    if (document.links == null || document.links.self == null || isBlank(document.links.self.href)) {
      throw new CdamUploadException(
          "CDAM upload of bundle '" + artifact.fileName() + "' returned no document self link");
    }
    return document.links.self.href;
  }

  private String binaryLink(BundleArtifact artifact, Document document) {
    if (document.links == null || document.links.binary == null || isBlank(document.links.binary.href)) {
      throw new CdamUploadException(
          "CDAM upload of bundle '" + artifact.fileName() + "' returned no document binary link");
    }
    return document.links.binary.href;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record Tokens(String systemUserToken, String serviceToken) {
  }
}
