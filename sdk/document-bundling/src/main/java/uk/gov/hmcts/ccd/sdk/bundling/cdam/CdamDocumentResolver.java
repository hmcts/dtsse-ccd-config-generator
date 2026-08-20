package uk.gov.hmcts.ccd.sdk.bundling.cdam;

import feign.FeignException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

/**
 * The built-in resolver for CDAM-sourced inputs. A {@link DocumentReference} with provider
 * {@value #PROVIDER} carries the CDAM document UUID as its id.
 *
 * <p>Each reference costs exactly one authorised call — {@code getDocumentBinary} — because the
 * binary response already carries everything the pipeline needs: the file name in the
 * {@code OriginalFileName} header (with {@code Content-Disposition} as the fallback), the media
 * type in {@code Content-Type}, and the length in {@code Content-Length}. The per-document
 * metadata call the current stitching service makes is deliberately dropped; it duplicates the
 * identical authorisation chain for two fields the binary response already supplies. Header
 * values are read raw and never parsed strictly — dm-store persists uploader-supplied media
 * types verbatim, so a malformed header must degrade, not fail; content-based type detection in
 * the pipeline is the backstop for the declared media type. File names from the headers are
 * sanitised: path separators, control characters, and leading dots are stripped, with the
 * document UUID as the fallback when nothing safe remains.
 *
 * <p>References are fetched sequentially and each binary is spooled to an owner-only file in the
 * configured spool directory as soon as it is fetched, so peak heap stays at roughly one
 * document rather than the sum of the batch (the client's Feign decoder buffers each binary
 * fully before the adapter sees it — see the package documentation for that residual
 * limitation). Each {@link ResolvedDocument} streams from its spooled file and deletes it on
 * close.
 *
 * <p>Failures are mapped per reference to typed {@link ResolutionFailureReason}s and never abort
 * the batch:
 *
 * <ul>
 * <li>HTTP 404 maps to {@code NOT_FOUND};
 * <li>HTTP 401 and 403 map to {@code ACCESS_DENIED};
 * <li>HTTP 408 and 429, all 5xx statuses, transport failures, empty responses, spooling I/O
 * failures, and unexpected runtime failures map to {@code TRANSIENT_FAILURE};
 * <li>all other 4xx statuses (for example 400, 415, 422) and malformed header shapes map to
 * {@code INVALID_CONTENT}, so durable jobs do not retry permanent failures;
 * <li>a token acquisition failure fails every reference as {@code TRANSIENT_FAILURE} with a
 * detail naming token acquisition — CDAM is never called.
 *
 * </ul>
 *
 * <p>Failure details name the document, the HTTP status, or the exception class only — never a
 * raw response body, a downstream exception message, or authorisation material. Duplicate
 * references in the input are resolved once.
 */
public final class CdamDocumentResolver implements DocumentResolver {

  /** The provider name CDAM references use. */
  public static final String PROVIDER = "cdam";

  private static final String ORIGINAL_FILE_NAME_HEADER = "OriginalFileName";
  private static final String FALLBACK_MEDIA_TYPE = "application/octet-stream";
  private static final Pattern CONTENT_DISPOSITION_FILENAME =
      Pattern.compile("filename=(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
  private static final Pattern UNSAFE_FILENAME_CHARACTERS = Pattern.compile("[\\p{Cntrl}]");
  private static final Set<PosixFilePermission> SPOOL_DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> SPOOL_FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");

  private final CaseDocumentClientApi caseDocumentClientApi;
  private final BundlingAuthenticationProvider authenticationProvider;
  private final Path spoolDirectory;

  /**
   * Creates the resolver.
   *
   * @param caseDocumentClientApi the CDAM client
   * @param authenticationProvider the system-user authentication port
   * @param spoolDirectory the directory fetched binaries are spooled into; created on demand
   *     with owner-only permissions
   */
  public CdamDocumentResolver(
      CaseDocumentClientApi caseDocumentClientApi,
      BundlingAuthenticationProvider authenticationProvider,
      Path spoolDirectory) {
    if (caseDocumentClientApi == null) {
      throw new IllegalArgumentException("CdamDocumentResolver.caseDocumentClientApi must be provided");
    }
    if (authenticationProvider == null) {
      throw new IllegalArgumentException("CdamDocumentResolver.authenticationProvider must be provided");
    }
    if (spoolDirectory == null) {
      throw new IllegalArgumentException("CdamDocumentResolver.spoolDirectory must be provided");
    }
    this.caseDocumentClientApi = caseDocumentClientApi;
    this.authenticationProvider = authenticationProvider;
    this.spoolDirectory = spoolDirectory;
  }

  @Override
  public String provider() {
    return PROVIDER;
  }

  @Override
  public ResolvedDocuments resolveAll(List<DocumentReference> references, BundleExecutionContext context) {
    Map<DocumentReference, ResolvedDocument> resolved = new LinkedHashMap<>();
    Map<DocumentReference, ResolutionFailure> failures = new LinkedHashMap<>();

    String systemUserToken;
    String serviceToken;
    try {
      systemUserToken = authenticationProvider.systemUserToken();
      serviceToken = authenticationProvider.serviceToken();
    } catch (RuntimeException e) {
      // A token failure is not a CDAM failure: fail every reference typed, without calling CDAM.
      ResolutionFailure failure = new ResolutionFailure(
          ResolutionFailureReason.TRANSIENT_FAILURE,
          "System user token acquisition failed (" + e.getClass().getSimpleName()
              + ") before any CDAM call");
      for (DocumentReference reference : references) {
        failures.putIfAbsent(reference, failure);
      }
      return new ResolvedDocuments(resolved, failures);
    }

    for (DocumentReference reference : references) {
      if (resolved.containsKey(reference) || failures.containsKey(reference)) {
        continue;
      }
      try {
        resolved.put(reference, fetch(systemUserToken, serviceToken, reference));
      } catch (ResolutionProblem problem) {
        failures.put(reference, problem.failure);
      } catch (RuntimeException e) {
        failures.put(reference, unexpectedFailure(e, reference));
      }
    }
    return new ResolvedDocuments(resolved, failures);
  }

  private static ResolutionFailure unexpectedFailure(RuntimeException e, DocumentReference reference) {
    // Malformed header shapes surface as parse failures; anything else is treated as transient.
    ResolutionFailureReason reason =
        e instanceof NumberFormatException || e instanceof InvalidMediaTypeException
            ? ResolutionFailureReason.INVALID_CONTENT
            : ResolutionFailureReason.TRANSIENT_FAILURE;
    return new ResolutionFailure(
        reason,
        "Unexpected " + e.getClass().getSimpleName() + " resolving document " + reference.id());
  }

  private ResolvedDocument fetch(String systemUserToken, String serviceToken, DocumentReference reference) {
    UUID documentId = documentId(reference);
    ResponseEntity<Resource> response = fetchBinary(systemUserToken, serviceToken, documentId);

    Resource body = response.getBody();
    if (body == null) {
      throw new ResolutionProblem(
          ResolutionFailureReason.TRANSIENT_FAILURE,
          "CDAM returned an empty binary response for document " + documentId);
    }

    HttpHeaders headers = response.getHeaders();
    Path spooled = spool(body, documentId);
    return new CdamResolvedDocument(spooled, mediaType(headers), fileName(headers, documentId),
        contentLength(headers));
  }

  /**
   * Spools the buffered binary to an owner-only file so the heap reference can be released
   * before the next reference is fetched.
   */
  private Path spool(Resource body, UUID documentId) {
    Path spooled = null;
    try {
      Files.createDirectories(spoolDirectory, directoryAttributes());
      spooled = Files.createTempFile(spoolDirectory, "cdam-", ".spool", fileAttributes());
      try (InputStream in = body.getInputStream();
          var out = Files.newOutputStream(spooled)) {
        in.transferTo(out);
      }
      return spooled;
    } catch (IOException e) {
      deleteQuietly(spooled);
      throw new ResolutionProblem(
          ResolutionFailureReason.TRANSIENT_FAILURE,
          "Could not spool the CDAM binary for document " + documentId);
    }
  }

  private FileAttribute<?>[] directoryAttributes() {
    return posixSupported()
        ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(SPOOL_DIRECTORY_PERMISSIONS)}
        : new FileAttribute<?>[0];
  }

  private FileAttribute<?>[] fileAttributes() {
    return posixSupported()
        ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(SPOOL_FILE_PERMISSIONS)}
        : new FileAttribute<?>[0];
  }

  private boolean posixSupported() {
    return spoolDirectory.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  private static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      // Best effort: the spool directory is job-scoped and cleaned up with the job.
    }
  }

  private static UUID documentId(DocumentReference reference) {
    try {
      return UUID.fromString(reference.id());
    } catch (IllegalArgumentException e) {
      throw new ResolutionProblem(
          ResolutionFailureReason.NOT_FOUND,
          "Reference id '" + reference.id() + "' is not a CDAM document UUID");
    }
  }

  private ResponseEntity<Resource> fetchBinary(String systemUserToken, String serviceToken, UUID documentId) {
    ResponseEntity<Resource> response;
    try {
      response = caseDocumentClientApi.getDocumentBinary(systemUserToken, serviceToken, documentId);
    } catch (FeignException e) {
      throw statusProblem(e.status(), documentId);
    } catch (RuntimeException e) {
      // Only the exception type is safe to surface; downstream messages may embed bodies or URLs.
      throw new ResolutionProblem(
          ResolutionFailureReason.TRANSIENT_FAILURE,
          "Unexpected " + e.getClass().getSimpleName() + " during CDAM binary fetch for document " + documentId);
    }
    if (response == null) {
      throw new ResolutionProblem(
          ResolutionFailureReason.TRANSIENT_FAILURE,
          "CDAM returned an empty binary response for document " + documentId);
    }
    if (!response.getStatusCode().is2xxSuccessful()) {
      throw statusProblem(response.getStatusCode().value(), documentId);
    }
    return response;
  }

  private static ResolutionProblem statusProblem(int status, UUID documentId) {
    if (status <= 0) {
      return new ResolutionProblem(
          ResolutionFailureReason.TRANSIENT_FAILURE,
          "CDAM binary fetch failed without an HTTP response for document " + documentId);
    }
    return new ResolutionProblem(
        reasonForStatus(status), "CDAM returned HTTP " + status + " for document " + documentId);
  }

  private static ResolutionFailureReason reasonForStatus(int status) {
    if (status == 404) {
      return ResolutionFailureReason.NOT_FOUND;
    }
    if (status == 401 || status == 403) {
      return ResolutionFailureReason.ACCESS_DENIED;
    }
    if (status == 408 || status == 429 || status >= 500) {
      return ResolutionFailureReason.TRANSIENT_FAILURE;
    }
    if (status >= 400) {
      // Remaining 4xx statuses are permanent for this document; retrying cannot fix them.
      return ResolutionFailureReason.INVALID_CONTENT;
    }
    return ResolutionFailureReason.TRANSIENT_FAILURE;
  }

  private static String fileName(HttpHeaders headers, UUID documentId) {
    String originalFileName = headers.getFirst(ORIGINAL_FILE_NAME_HEADER);
    if (originalFileName != null && !originalFileName.isBlank()) {
      return sanitiseFileName(originalFileName, documentId);
    }
    String contentDisposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
    if (contentDisposition != null) {
      Matcher matcher = CONTENT_DISPOSITION_FILENAME.matcher(contentDisposition);
      if (matcher.find()) {
        String fileName = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        if (!fileName.isBlank()) {
          return sanitiseFileName(fileName, documentId);
        }
      }
    }
    return documentId.toString();
  }

  /**
   * Strips path separators, control characters, and leading dots from a header-supplied file
   * name, keeping only the final path segment; falls back to the document UUID when nothing
   * safe remains.
   */
  private static String sanitiseFileName(String fileName, UUID documentId) {
    String stripped = UNSAFE_FILENAME_CHARACTERS.matcher(fileName).replaceAll("");
    int lastSeparator = Math.max(stripped.lastIndexOf('/'), stripped.lastIndexOf('\\'));
    if (lastSeparator >= 0) {
      stripped = stripped.substring(lastSeparator + 1);
    }
    int firstSafe = 0;
    while (firstSafe < stripped.length() && stripped.charAt(firstSafe) == '.') {
      firstSafe++;
    }
    stripped = stripped.substring(firstSafe).trim();
    return stripped.isEmpty() ? documentId.toString() : stripped;
  }

  private static String mediaType(HttpHeaders headers) {
    // Raw header read on purpose: HttpHeaders.getContentType() throws for malformed values, and
    // dm-store persists uploader-supplied media types verbatim. The pipeline's content-based
    // detection is the backstop for whatever is declared here.
    String contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
    if (contentType == null || contentType.isBlank()) {
      return FALLBACK_MEDIA_TYPE;
    }
    return contentType.trim();
  }

  private static OptionalLong contentLength(HttpHeaders headers) {
    // Defensive parse: HttpHeaders.getContentLength() throws NumberFormatException on garbage.
    String contentLength = headers.getFirst(HttpHeaders.CONTENT_LENGTH);
    if (contentLength == null || contentLength.isBlank()) {
      return OptionalLong.empty();
    }
    try {
      long length = Long.parseLong(contentLength.trim());
      return length >= 0 ? OptionalLong.of(length) : OptionalLong.empty();
    } catch (NumberFormatException e) {
      return OptionalLong.empty();
    }
  }

  /**
   * One resolved CDAM binary, streaming from its spooled file; the file is deleted on close.
   */
  private static final class CdamResolvedDocument implements ResolvedDocument {

    private final Path spooled;
    private final String mediaType;
    private final String fileName;
    private final OptionalLong contentLength;
    private InputStream content;

    private CdamResolvedDocument(
        Path spooled, String mediaType, String fileName, OptionalLong contentLength) {
      this.spooled = spooled;
      this.mediaType = mediaType;
      this.fileName = fileName;
      this.contentLength = contentLength;
    }

    @Override
    public InputStream content() {
      if (content == null) {
        try {
          content = Files.newInputStream(spooled);
        } catch (IOException e) {
          throw new UncheckedIOException("Could not open the spooled content for " + fileName, e);
        }
      }
      return content;
    }

    @Override
    public String mediaType() {
      return mediaType;
    }

    @Override
    public String fileName() {
      return fileName;
    }

    @Override
    public OptionalLong contentLength() {
      return contentLength;
    }

    @Override
    public Optional<String> checksum() {
      return Optional.empty();
    }

    @Override
    public void close() throws IOException {
      try {
        if (content != null) {
          content.close();
        }
      } finally {
        Files.deleteIfExists(spooled);
      }
    }
  }

  /** Internal control-flow carrier for one reference's typed failure. */
  private static final class ResolutionProblem extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ResolutionFailure failure;

    private ResolutionProblem(ResolutionFailureReason reason, String detail) {
      super(detail);
      this.failure = new ResolutionFailure(reason, detail);
    }
  }
}
