package uk.gov.hmcts.ccd.sdk.bundling.cdam;

import uk.gov.hmcts.reform.ccd.document.am.model.Classification;

/**
 * The consuming service's CDAM upload coordinates: which jurisdiction and case type the stored
 * bundle belongs to, its security classification, and whether the destination attaches the
 * uploaded document to its case.
 *
 * <p>The identity fields are required and none is defaulted. In particular the classification is
 * explicit configuration by design: the current stitching service hardcodes
 * {@link Classification#PUBLIC} on upload, and that is deliberately not replicated — an adapter
 * must not default a bundle containing restricted material to public.
 *
 * <p>{@code attachToCase} chooses between the platform's two attachment shapes. A CDAM upload
 * that is never associated with a case is disposed of when its time-to-live expires, so one of
 * the two must happen:
 *
 * <ul>
 * <li>{@code false} (the default): the consuming service attaches by submitting the stored
 * document — {@code CcdBundle.stitchedDocument}, whose {@code document_hash} carries the CDAM
 * hash token — through a case-data path the platform scans: a legacy callback response persisted
 * by CCD, or the decentralised runtime's own CDAM attach for legacy-callback events. This is the
 * only shape available to services whose S2S identity holds no CDAM {@code ATTACH} permission
 * (in the centralised model only {@code ccd_data} attaches).
 * <li>{@code true}: the destination itself calls CDAM's {@code /cases/documents/attachToCase}
 * with the uploaded document's hash token immediately after the upload, using the case reference
 * from the execution context. This is the shape for decentralised submit-handler events, where
 * no platform component ever sees the document in case data; it requires the service's S2S
 * identity to be onboarded with CDAM {@code ATTACH} permission, exactly as the decentralised
 * runtime's own attach path does.
 * </ul>
 *
 * @param jurisdictionId the CCD jurisdiction id the bundle document is uploaded under
 * @param caseTypeId the CCD case type id the bundle document is uploaded under
 * @param classification the explicit security classification of the stored bundle
 * @param attachToCase whether the destination attaches the upload to the context's case
 */
public record CdamUploadSettings(
    String jurisdictionId,
    String caseTypeId,
    Classification classification,
    boolean attachToCase) {

  public CdamUploadSettings {
    if (jurisdictionId == null || jurisdictionId.isBlank()) {
      throw new IllegalArgumentException("CdamUploadSettings.jurisdictionId must be provided and non-blank");
    }
    if (caseTypeId == null || caseTypeId.isBlank()) {
      throw new IllegalArgumentException("CdamUploadSettings.caseTypeId must be provided and non-blank");
    }
    if (classification == null) {
      throw new IllegalArgumentException(
          "CdamUploadSettings.classification must be explicit; the SDK never defaults an upload classification");
    }
  }

  /**
   * Creates upload settings that leave attachment to the consuming service's own case-data path
   * (see the class documentation for when that is the right shape).
   *
   * @param jurisdictionId the CCD jurisdiction id the bundle document is uploaded under
   * @param caseTypeId the CCD case type id the bundle document is uploaded under
   * @param classification the explicit security classification of the stored bundle
   */
  public CdamUploadSettings(String jurisdictionId, String caseTypeId, Classification classification) {
    this(jurisdictionId, caseTypeId, classification, false);
  }
}
