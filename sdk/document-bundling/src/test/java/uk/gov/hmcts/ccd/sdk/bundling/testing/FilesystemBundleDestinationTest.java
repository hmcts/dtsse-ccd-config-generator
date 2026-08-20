package uk.gov.hmcts.ccd.sdk.bundling.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.StoredBundle;

class FilesystemBundleDestinationTest {

  private static final byte[] PDF_BYTES = "pdf-bytes".getBytes(StandardCharsets.UTF_8);

  @TempDir
  private Path tempDir;

  @Test
  void roundTripsTheArtifactWithOwnerOnlyPermissions() throws Exception {
    Path directory = tempDir.resolve("bundles");
    FilesystemBundleDestination destination = new FilesystemBundleDestination(directory);

    StoredBundle stored = destination.store(artifact("hearing-bundle.pdf"), BundleExecutionContext.empty());

    Path file = directory.resolve("hearing-bundle.pdf");
    assertThat(file).exists();
    assertThat(Files.readAllBytes(file)).isEqualTo(PDF_BYTES);
    assertThat(stored.url()).startsWith("file:").endsWith("hearing-bundle.pdf");
    assertThat(stored.binaryUrl()).isEqualTo(stored.url());
    assertThat(Path.of(URI.create(stored.url()))).isEqualTo(file.toAbsolutePath());
    assertThat(stored.filename()).isEqualTo("hearing-bundle.pdf");
    assertThat(stored.mediaType()).isEqualTo("application/pdf");
    assertThat(stored.size()).isEqualTo(PDF_BYTES.length);
    assertThat(stored.sha256()).isEqualTo("test-sha256");
    assertThat(stored.hashToken()).isEmpty();

    assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Set<PosixFilePermission> filePermissions = Files.getPosixFilePermissions(file);
    assertThat(filePermissions).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    Set<PosixFilePermission> directoryPermissions = Files.getPosixFilePermissions(directory);
    assertThat(directoryPermissions).isEqualTo(PosixFilePermissions.fromString("rwx------"));
  }

  @Test
  void overwritesAnExistingFileAtomicallyFromTheCallersPointOfView() throws Exception {
    Path directory = tempDir.resolve("bundles");
    FilesystemBundleDestination destination = new FilesystemBundleDestination(directory);
    destination.store(artifact("hearing-bundle.pdf"), BundleExecutionContext.empty());

    destination.store(artifact("hearing-bundle.pdf"), BundleExecutionContext.empty());

    assertThat(Files.readAllBytes(directory.resolve("hearing-bundle.pdf"))).isEqualTo(PDF_BYTES);
  }

  @Test
  void rejectsFileNamesWithPathSegments() {
    FilesystemBundleDestination destination = new FilesystemBundleDestination(tempDir);

    assertThatThrownBy(() -> destination.store(artifact("../escape.pdf"), BundleExecutionContext.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("plain file name");
  }

  private static BundleArtifact artifact(String fileName) {
    return new BundleArtifact() {
      @Override
      public String fileName() {
        return fileName;
      }

      @Override
      public String mediaType() {
        return "application/pdf";
      }

      @Override
      public long size() {
        return PDF_BYTES.length;
      }

      @Override
      public String sha256() {
        return "test-sha256";
      }

      @Override
      public int pageCount() {
        return 1;
      }

      @Override
      public InputStream open() {
        return new ByteArrayInputStream(PDF_BYTES);
      }
    };
  }
}
