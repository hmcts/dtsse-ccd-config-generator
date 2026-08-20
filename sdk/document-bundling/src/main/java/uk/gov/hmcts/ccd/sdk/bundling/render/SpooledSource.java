package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import uk.gov.hmcts.ccd.sdk.bundling.convert.FileBackedSource;

/**
 * A resolved source whose content the pipeline has already spooled to the job's temporary
 * directory: what handlers receive for every fetched document. The media type carried here is the
 * effective (post-detection) type the document was routed on. Closing is a no-op — the pipeline
 * owns the spooled file and deletes it with the job directory.
 *
 * @param file the spooled content file
 * @param mediaType the effective media type the document was routed on
 * @param fileName the source file name reported by the resolver
 * @param size the spooled size in bytes
 * @param sha256 the hex-encoded SHA-256 of the spooled content
 * @param providerChecksum the resolver-reported checksum, when it supplied one
 */
record SpooledSource(
    Path file,
    String mediaType,
    String fileName,
    long size,
    String sha256,
    Optional<String> providerChecksum) implements FileBackedSource {

  @Override
  public InputStream content() {
    try {
      return Files.newInputStream(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not reopen the spooled source file", e);
    }
  }

  @Override
  public OptionalLong contentLength() {
    return OptionalLong.of(size);
  }

  @Override
  public Optional<String> checksum() {
    return providerChecksum;
  }

  @Override
  public void close() {
    // The pipeline owns the spooled file; it is deleted with the job's temporary directory.
  }

  /**
   * A copy of this source routed on a different effective media type.
   *
   * @param effectiveType the post-detection media type
   * @return the rerouted copy
   */
  SpooledSource withMediaType(String effectiveType) {
    return new SpooledSource(file, effectiveType, fileName, size, sha256, providerChecksum);
  }
}
