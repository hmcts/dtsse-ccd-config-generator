package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.nio.file.Path;
import java.util.function.Supplier;
import org.slf4j.MDC;
import uk.gov.hmcts.ccd.sdk.doctoolkit.cdam.CaseLocator;
import uk.gov.hmcts.ccd.sdk.doctoolkit.cdam.CdamDocuments;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.DocumentConverter;
import uk.gov.hmcts.ccd.sdk.doctoolkit.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.doctoolkit.render.Renderer;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

/**
 * Turns documents into PDFs: bundles with contents and page numbers, single documents, and
 * print-ready letters for bulk print. Spring Boot services get one auto-configured; inject it.
 *
 * <pre>{@code
 * try (PdfResult letter = docToolkit.render(PdfRequest.builder("Claim pack")
 *     .document(coversheet).document(claimForm).print(PrintOptions.bulkPrint()).build())) {
 *   sendLetter(letter.open());
 * }
 * }</pre>
 *
 * <p>Rendering is synchronous and CPU- and memory-heavy for large bundles; it runs on the
 * caller's thread. The toolkit is thread-safe. To store a document on a case, submit it to
 * {@link DocJobs} instead, so it renders in the background, outside the case event.
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
   * Renders the request to a temporary PDF, for output the service sends elsewhere (bulk print),
   * previews and tests. Close the result to delete it.
   *
   * @throws DocToolkitException if it cannot be rendered
   */
  public PdfResult render(PdfRequest request) {
    Validate.requireNonNull(request, "request");
    return renderer.render(request);
  }

  /**
   * A rendered document in CDAM, not yet attached; {@code hashToken} is what attaching needs.
   */
  record Stored(UploadedDoc uploaded, String hashToken) {
  }

  /**
   * Renders the request and stores the PDF in CDAM without attaching it to the case, so CDAM's
   * time to live deletes it unless it is {@link #attach attached}.
   */
  Stored renderAndStore(PdfRequest request, long caseReference) {
    Validate.requireNonNull(request, "request");
    requireCdam();
    // A worker running a job has already put the case in the logging context; leave it there.
    boolean addedCase = MDC.get("caseReference") == null;
    if (addedCase) {
      MDC.put("caseReference", String.valueOf(caseReference));
    }
    try (PdfResult result = renderer.render(request)) {
      CdamDocuments.Stored stored;
      try {
        stored = cdam.store(result.file(), result.fileName(), caseReference,
            request.classification().name());
      } catch (RuntimeException e) {
        throw new DocToolkitException(DocToolkitException.Reason.UPLOAD_FAILED,
            result.fileName() + " could not be stored in CDAM for case " + caseReference + ": "
                + e.getMessage(), true, e);
      }
      request.category().ifPresent(stored.document()::setCategoryId);
      return new Stored(new UploadedDoc(stored.document(), result.pageCount(), result.failures()),
          stored.hashToken());
    } finally {
      if (addedCase) {
        MDC.remove("caseReference");
      }
    }
  }

  /** Attaches a stored document to its case; attaching it again does nothing. */
  void attach(Stored stored, long caseReference) {
    requireCdam();
    try {
      cdam.attach(new CdamDocuments.Stored(stored.uploaded().document(), stored.hashToken()),
          caseReference);
    } catch (RuntimeException e) {
      throw new DocToolkitException(DocToolkitException.Reason.UPLOAD_FAILED,
          stored.uploaded().document().getUrl() + " could not be attached to case "
              + caseReference + ": " + e.getMessage(), true, e);
    }
  }

  private void requireCdam() {
    if (cdam == null) {
      throw new DocToolkitException(DocToolkitException.Reason.NOT_CONFIGURED,
          "Uploading needs CDAM: configure its client and service token, and supply a"
              + " CdamSystemUserTokenProvider bean or configure ccd.doc-toolkit.system-user.",
          false, null);
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
     * CDAM, for fetching {@link DocSource#of CDAM documents} and storing what jobs render.
     *
     * @param systemUser a bearer token for the user documents are fetched and stored as
     * @param cases finds a case's type and jurisdiction, which CDAM needs to attach a document
     */
    public Builder cdam(CaseDocumentClientApi client, AuthTokenGenerator serviceTokens,
        Supplier<String> systemUser, CaseLocator cases) {
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
