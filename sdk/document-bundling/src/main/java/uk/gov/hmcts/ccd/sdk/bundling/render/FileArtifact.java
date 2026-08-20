package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;

/**
 * The finished bundle as a file in the job's temporary directory. The backing file is deleted
 * with the job directory, so the destination must consume the stream during
 * {@code BundleDestination.store} — both the CDAM and filesystem destinations do.
 *
 * @param file the validated output PDF
 * @param fileName the output file name from the request
 * @param size the artifact size in bytes
 * @param sha256 the hex-encoded SHA-256 of the artifact
 * @param pageCount the artifact's total page count
 */
record FileArtifact(Path file, String fileName, long size, String sha256, int pageCount)
    implements BundleArtifact {

  @Override
  public String mediaType() {
    return "application/pdf";
  }

  @Override
  public InputStream open() throws IOException {
    return Files.newInputStream(file);
  }
}
