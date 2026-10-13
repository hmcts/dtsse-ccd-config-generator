package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import uk.gov.hmcts.ccd.sdk.bundling.convert.FileBackedSource;

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

  SpooledSource withMediaType(String effectiveType) {
    return new SpooledSource(file, effectiveType, fileName, size, sha256, providerChecksum);
  }
}
