package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.nio.file.Path;
import org.slf4j.MDC;
import uk.gov.hmcts.ccd.sdk.doctoolkit.cdam.CaseLocator;
import uk.gov.hmcts.ccd.sdk.doctoolkit.cdam.CdamDocuments;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.DocumentConverter;
import uk.gov.hmcts.ccd.sdk.doctoolkit.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.doctoolkit.render.Renderer;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

/**
 * Turns documents into PDFs: bundles with contents and page numbers, single documents, and
 * print-ready letters for bulk print. Spring Boot services get one auto-configured; inject it.
 *
 * <pre>{@code
 * UploadedDoc bundle = docToolkit.renderAndUpload(
 *     BundleRequest.builder("Hearing bundle")
 *         .section(BundleSection.builder("Evidence").document(caseData.getStatement()).build())
 *         .build(),
 *     caseId, eventId);
 * caseData.setHearingBundle(bundle.document());
 * }</pre>
 *
 * <p>Rendering is synchronous and CPU- and memory-heavy for large bundles; it runs on the
 * caller's thread. The toolkit is thread-safe.
 */
public final class DocToolkit {
  private final Renderer renderer;
  private final CdamDocuments cdam;

  private DocToolkit(Renderer renderer, CdamDocuments cdam) {
    this.renderer = renderer;
    this.cdam = cdam;
  }

  /** Starts a toolkit for use without Spring, or in tests. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Renders the request to a temporary PDF. Close the result to delete it.
   *
   * @throws DocToolkitException if it cannot be rendered
   */
  public DocResult render(DocRequest request) {
    Validate.requireNonNull(request, "request");
    return renderer.render(request);
  }

  /**
   * Renders the request, stores the PDF in CDAM as the system user and attaches it to the case.
   *
   * @param caseId the case the document is attached to
   * @param eventId the event rendering it, for logs
   * @throws DocToolkitException if it cannot be rendered or stored
   */
  public UploadedDoc renderAndUpload(DocRequest request, long caseId, String eventId) {
    return renderAndUpload(request, caseId, eventId, true);
  }

  /**
   * As {@link #renderAndUpload(DocRequest, long, String)}; a queued job's retries pass false.
   */
  UploadedDoc renderAndUpload(DocRequest request, long caseId, String eventId,
      boolean finalAttempt) {
    Validate.requireNonNull(request, "request");
    if (cdam == null) {
      throw new DocToolkitException(DocToolkitException.Reason.NOT_CONFIGURED,
          "Uploading needs CDAM: the service's CaseDocumentClientApi, AuthTokenGenerator and a"
              + " DocToolkitSystemUser bean.", false, null);
    }
    try (MDC.MDCCloseable ignoredCase = MDC.putCloseable("caseId", String.valueOf(caseId));
        MDC.MDCCloseable ignoredEvent = MDC.putCloseable("eventId", String.valueOf(eventId));
        DocResult result = renderer.render(request, finalAttempt)) {
      Document document;
      try {
        document = cdam.upload(result.file(), result.fileName(), caseId);
      } catch (RuntimeException e) {
        throw new DocToolkitException(DocToolkitException.Reason.UPLOAD_FAILED,
            result.fileName() + " could not be stored in CDAM for case " + caseId + ": "
                + e.getMessage(), true, e);
      }
      return new UploadedDoc(document, result.pageCount(), result.failures());
    }
  }

  /**
   * Builds a {@link DocToolkit}.
   */
  public static final class Builder {
    private DocmosisRenderService docmosis;
    private Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"));
    private CdamDocuments cdam;

    private Builder() {
    }

    /** The Docmosis client for office formats. Without it they cannot be converted. */
    public Builder docmosis(DocmosisRenderService docmosis) {
      this.docmosis = Validate.requireNonNull(docmosis, "docmosis");
      return this;
    }

    /**
     * CDAM, for fetching {@link DocSource#of CDAM documents} and for
     * {@link #renderAndUpload uploading}.
     *
     * @param cases finds a case's type and jurisdiction, which CDAM needs to attach a document
     */
    public Builder cdam(CaseDocumentClientApi client, AuthTokenGenerator serviceTokens,
        DocToolkitSystemUser systemUser, CaseLocator cases) {
      this.cdam = new CdamDocuments(Validate.requireNonNull(client, "client"),
          Validate.requireNonNull(serviceTokens, "serviceTokens"),
          Validate.requireNonNull(systemUser, "systemUser"),
          Validate.requireNonNull(cases, "cases"));
      return this;
    }

    /**
     * Where documents are fetched and rendered. Defaults to {@code java.io.tmpdir}.
     */
    public Builder tempDirectory(Path tempDirectory) {
      this.tempDirectory = Validate.requireNonNull(tempDirectory, "tempDirectory");
      return this;
    }

    /** Builds the toolkit. */
    public DocToolkit build() {
      DocumentConverter.Builder converter = DocumentConverter.builder().tempDirectory(tempDirectory);
      if (docmosis != null) {
        converter.docmosis(docmosis);
      }
      return new DocToolkit(new Renderer(converter.build(), cdam, tempDirectory), cdam);
    }
  }
}
