package uk.gov.hmcts.ccd.sdk.bundling.docmosis;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DocmosisRenderService} over the JDK HTTP client, speaking the exact multipart protocol
 * {@code em-stitching-api} already uses against the shared per-environment Docmosis instance:
 * {@code accessKey}/{@code outputName}/{@code file} parts to {@code /rs/convert}, pinned to
 * HTTP/1.1 so the wire matches what Docmosis sees from the current OkHttp clients.
 *
 * <p>It deliberately does not carry over the known defects of the current integration: the
 * {@code file} part is tagged with the source's real media type rather than a hard-coded
 * {@code application/pdf} (sanitised to a plain {@code type/subtype} token so document-store
 * metadata cannot inject part headers); responses stream to a caller-owned temp file instead of
 * being buffered in memory; the source-size ceiling is enforced before anything is sent; the
 * read timeout bounds the whole exchange including the streamed body, not just the response
 * headers; a 2xx response
 * whose body is not a PDF is a typed non-transient failure rather than garbage propagated into
 * the pipeline; transient failures (connect/IO errors, timeouts, and 5xx responses) are flagged
 * as such so callers can decide whether to resubmit while 4xx responses are permanent; and
 * failure messages are log-safe — they carry the status code and a short classification, never
 * the access key or the raw response body.
 */
public final class HttpDocmosisRenderService implements DocmosisRenderService {
  private static final Logger log = LoggerFactory.getLogger(HttpDocmosisRenderService.class);

  private static final String PDF_CONTENT_TYPE = "application/pdf";
  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
  private static final Pattern MEDIA_TYPE_TOKEN = Pattern.compile(
      "^[A-Za-z0-9!#$%&'*+.^_`|~-]+/[A-Za-z0-9!#$%&'*+.^_`|~-]+");
  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

  private final DocmosisConnection connection;
  private final Path outputDirectory;
  private final HttpClient httpClient;

  /** Creates the service. */
  public HttpDocmosisRenderService(DocmosisConnection connection, Path outputDirectory) {
    this.connection = Objects.requireNonNull(connection, "connection must be provided");
    this.outputDirectory =
        Objects.requireNonNull(outputDirectory, "outputDirectory must be provided");
    this.httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(connection.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Override
  public Path convertToPdf(Path source, String fileName, String mediaType)
      throws DocmosisRenderException {
    Objects.requireNonNull(source, "source must be provided");
    Objects.requireNonNull(fileName, "fileName must be provided");
    Objects.requireNonNull(mediaType, "mediaType must be provided");
    enforceSizeCeiling(source, fileName);
    String safeMediaType = sanitiseMediaType(mediaType);
    MultipartBody body = new MultipartBody();
    body.addField("accessKey", connection.accessKey());
    body.addField("outputName", fileName + ".pdf");
    addSourcePart(body, source, fileName, safeMediaType);
    return execute("convert", fileName, connection.convertEndpoint(), body);
  }

  private void enforceSizeCeiling(Path source, String fileName) throws DocmosisRenderException {
    long size;
    try {
      size = Files.size(source);
    } catch (IOException e) {
      throw new DocmosisRenderException(
          "Could not read the size of source file " + fileName + " before conversion.", false, e);
    }
    if (size > connection.maxSourceBytes()) {
      throw new DocmosisRenderException(
          "Source " + fileName + " is " + size + " bytes, above the configured Docmosis ceiling"
              + " of " + connection.maxSourceBytes() + " bytes; it was not sent for conversion."
              + " Raise maxSourceBytes or reduce the document.",
          false);
    }
  }

  private void addSourcePart(MultipartBody body, Path source, String fileName, String mediaType)
      throws DocmosisRenderException {
    try {
      body.addFile("file", fileName, mediaType, source);
    } catch (FileNotFoundException e) {
      throw new DocmosisRenderException(
          "Source file for " + fileName + " does not exist or is not readable.", false, e);
    }
  }

  private Path execute(String operation, String subject, URI endpoint, MultipartBody body)
      throws DocmosisRenderException {
    Path target = createOutputFile();
    HttpRequest request = HttpRequest.newBuilder(endpoint)
        .timeout(connection.readTimeout())
        .header("Content-Type", body.contentType())
        .header("Accept", PDF_CONTENT_TYPE)
        .POST(body.publisher())
        .build();
    final long started = System.nanoTime();
    // The whole exchange — including the streamed body, which HttpRequest.timeout() alone does
    // not bound — must finish within the read timeout.
    CompletableFuture<HttpResponse<Path>> pending =
        httpClient.sendAsync(request, BodyHandlers.ofFile(target));
    HttpResponse<Path> response;
    try {
      response = pending.get(connection.readTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      pending.cancel(true);
      deleteQuietly(target);
      throw new DocmosisRenderException(
          "Docmosis " + operation + " of " + subject + " timed out after "
              + connection.readTimeout() + " before the response completed.",
          true, e);
    } catch (ExecutionException e) {
      deleteQuietly(target);
      throw mapExecutionFailure(operation, subject, e.getCause() == null ? e : e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pending.cancel(true);
      deleteQuietly(target);
      throw new DocmosisRenderException(
          "Docmosis " + operation + " of " + subject + " was interrupted.", false, e);
    }
    int status = response.statusCode();
    if (!isSuccess(status)) {
      deleteQuietly(target);
      boolean transientFailure = status >= 500;
      throw new DocmosisRenderException(
          "Docmosis " + operation + " of " + subject + " failed with HTTP " + status + " ("
              + (transientFailure ? "server error" : "client error")
              + "); response body withheld from logs.",
          transientFailure);
    }
    requirePdf(operation, subject, response, target);
    if (log.isDebugEnabled()) {
      log.debug("Docmosis {} of {} succeeded in {} ms",
          operation, subject, (System.nanoTime() - started) / 1_000_000);
    }
    return target;
  }

  private DocmosisRenderException mapExecutionFailure(
      String operation, String subject, Throwable cause) {
    if (hasCause(cause, HttpTimeoutException.class)) {
      return new DocmosisRenderException(
          "Docmosis " + operation + " of " + subject + " timed out after "
              + connection.readTimeout() + ".",
          true, cause);
    }
    return new DocmosisRenderException(
        "Docmosis " + operation + " of " + subject + " failed with an I/O error before the"
            + " response completed.",
        true, cause);
  }

  private void requirePdf(String operation, String subject, HttpResponse<Path> response,
      Path target) throws DocmosisRenderException {
    boolean pdf;
    try (InputStream in = Files.newInputStream(target)) {
      pdf = Arrays.equals(in.readNBytes(PDF_MAGIC.length), PDF_MAGIC);
    } catch (IOException e) {
      deleteQuietly(target);
      throw new DocmosisRenderException(
          "Docmosis " + operation + " of " + subject + " responded but the written file could"
              + " not be verified as a PDF.",
          false, e);
    }
    if (!pdf) {
      String contentType = sanitiseHeader(
          response.headers().firstValue("Content-Type").orElse("unknown"));
      deleteQuietly(target);
      throw new DocmosisRenderException(
          "Docmosis " + operation + " of " + subject + " returned HTTP " + response.statusCode()
              + " but the body is not a PDF (content-type: " + contentType
              + "); response body withheld from logs.",
          false);
    }
  }

  private Path createOutputFile() throws DocmosisRenderException {
    try {
      Files.createDirectories(outputDirectory);
      try {
        return Files.createTempFile(outputDirectory, "docmosis-", ".pdf", OWNER_ONLY);
      } catch (UnsupportedOperationException e) {
        // Non-POSIX file system; temp files are still private to the creating user by default.
        return Files.createTempFile(outputDirectory, "docmosis-", ".pdf");
      }
    } catch (IOException e) {
      throw new DocmosisRenderException(
          "Could not create an output file in " + outputDirectory + ".", false, e);
    }
  }

  private static boolean isSuccess(int status) {
    return status >= 200 && status < 300;
  }

  private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      if (type.isInstance(cause)) {
        return true;
      }
    }
    return false;
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Could not delete temporary file {}", path, e);
    }
  }

  private static String sanitiseMediaType(String mediaType) {
    Matcher matcher = MEDIA_TYPE_TOKEN.matcher(mediaType.trim());
    return matcher.find() ? matcher.group() : "application/octet-stream";
  }

  private static String sanitiseHeader(String value) {
    String cleaned = value.replaceAll("[^\\x20-\\x7E]", "");
    return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
  }

  private static String quote(String value) {
    return value.replace("\\", "%5C")
        .replace("\"", "%22")
        .replace("\r", "%0D")
        .replace("\n", "%0A");
  }

  /**
   * A hand-rolled {@code multipart/form-data} body. Field parts are buffered strings; the file
   * part streams from disk via {@link BodyPublishers#ofFile} so large sources are never held in
   * memory.
   */
  private static final class MultipartBody {
    private final String boundary = "ccd-sdk-" + UUID.randomUUID();
    private final List<BodyPublisher> parts = new ArrayList<>();

    void addField(String name, String value) {
      parts.add(BodyPublishers.ofString(
          "--" + boundary + "\r\n"
              + "Content-Disposition: form-data; name=\"" + quote(name) + "\"\r\n"
              + "\r\n"
              + value + "\r\n",
          StandardCharsets.UTF_8));
    }

    void addFile(String name, String fileName, String contentType, Path file)
        throws FileNotFoundException {
      parts.add(BodyPublishers.ofString(
          "--" + boundary + "\r\n"
              + "Content-Disposition: form-data; name=\"" + quote(name) + "\"; filename=\""
              + quote(fileName) + "\"\r\n"
              + "Content-Type: " + contentType + "\r\n"
              + "\r\n",
          StandardCharsets.UTF_8));
      parts.add(BodyPublishers.ofFile(file));
      parts.add(BodyPublishers.ofString("\r\n", StandardCharsets.UTF_8));
    }

    String contentType() {
      return "multipart/form-data; boundary=" + boundary;
    }

    BodyPublisher publisher() {
      List<BodyPublisher> all = new ArrayList<>(parts);
      all.add(BodyPublishers.ofString("--" + boundary + "--\r\n", StandardCharsets.UTF_8));
      return BodyPublishers.concat(all.toArray(BodyPublisher[]::new));
    }
  }
}
