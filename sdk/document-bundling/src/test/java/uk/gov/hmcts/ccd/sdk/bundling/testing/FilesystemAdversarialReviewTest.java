package uk.gov.hmcts.ccd.sdk.bundling.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;

/**
 * Adversarial review counter-examples for FilesystemBundleDestination. Every finding's proof
 * test is now enabled and asserts the fixed behaviour, serving as a live regression.
 */
class FilesystemAdversarialReviewTest {

  private static final byte[] FIRST_BUNDLE = "the-good-first-bundle".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SECOND_BUNDLE = "the-second-bundle-x".getBytes(StandardCharsets.UTF_8);

  @TempDir
  private Path tempDir;

  /**
   * FINDING F3, FIXED (atomic publication violated): {@code Files.copy(in, target,
   * REPLACE_EXISTING)} used to delete the existing file and stream into its place, so an
   * artifact stream that failed mid-read destroyed the previous successful bundle. The
   * destination now writes to a temporary file and atomically renames it over the target, so
   * "a failed job never replaces the last successful bundle" holds.
   */
  @Test
  void aFailedOverwriteMustNotDestroyThePreviousBundle() throws Exception {
    Path directory = tempDir.resolve("bundles");
    FilesystemBundleDestination destination = new FilesystemBundleDestination(directory);
    destination.store(artifact("hearing-bundle.pdf", FIRST_BUNDLE), BundleExecutionContext.empty());

    BundleArtifact failing = artifactWithStream("hearing-bundle.pdf", new InputStream() {
      private int served;

      @Override
      public int read() throws IOException {
        if (served++ > 4) {
          throw new IOException("stream died mid-transfer");
        }
        return 'x';
      }
    });

    assertThatThrownBy(() -> destination.store(failing, BundleExecutionContext.empty()))
        .hasMessageContaining("hearing-bundle.pdf");

    assertThat(Files.readAllBytes(directory.resolve("hearing-bundle.pdf")))
        .as("the last successful bundle must survive a failed replacement")
        .isEqualTo(FIRST_BUNDLE);
  }

  /**
   * FINDING F4, FIXED (permissions race): the file used to be created by Files.copy with default
   * (umask) permissions — typically world-readable — and only chmod-ed to rw------- AFTER all
   * bytes were written, so a bundle of legal documents was readable by other local users for the
   * whole duration of the write. Combined with the F3 fix, bytes now go into a temporary file
   * created with owner-only permissions before any byte is written, and the target path only
   * ever appears fully written via atomic rename. This test observes, from inside the artifact
   * stream (i.e. mid-copy), that the target does not exist yet and that every file present in
   * the directory is owner-only.
   */
  @Test
  void theBundleMustNeverBeReadableByOthersEvenMidWrite() throws Exception {
    Path directory = tempDir.resolve("bundles");
    assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
    FilesystemBundleDestination destination = new FilesystemBundleDestination(directory);
    Path target = directory.resolve("hearing-bundle.pdf");
    AtomicReference<Set<PosixFilePermission>> midWritePermissions = new AtomicReference<>();
    AtomicReference<Boolean> targetExistedMidWrite = new AtomicReference<>();

    BundleArtifact artifact = artifactWithStream("hearing-bundle.pdf", new InputStream() {
      private int index;

      @Override
      public int read() throws IOException {
        if (midWritePermissions.get() == null) {
          try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).findFirst().ifPresent(file -> {
              try {
                midWritePermissions.set(Files.getPosixFilePermissions(file));
                targetExistedMidWrite.set(Files.exists(target));
              } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
              }
            });
          }
        }
        return index < FIRST_BUNDLE.length ? FIRST_BUNDLE[index++] : -1;
      }
    });

    destination.store(artifact, BundleExecutionContext.empty());

    assertThat(targetExistedMidWrite.get())
        .as("the target path must not exist until the atomic rename")
        .isFalse();
    assertThat(midWritePermissions.get())
        .as("permissions observed on the in-progress file while the copy was running")
        .doesNotContain(
            PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);
  }

  /**
   * FINDING F5, FIXED (filename validation gap): safeFileName rejected "/", "\\", "." and ".."
   * but admitted the empty string — and {@code directory.resolve("")} IS the storage directory,
   * which the old REPLACE_EXISTING copy then replaced with a regular file. Blank names are now
   * rejected before any path work or I/O.
   */
  @Test
  void anEmptyFileNameMustBeRejectedBeforeItReplacesTheStorageDirectory() {
    Path directory = tempDir.resolve("bundles");
    FilesystemBundleDestination destination = new FilesystemBundleDestination(directory);

    assertThatThrownBy(() -> destination.store(artifact("", FIRST_BUNDLE), BundleExecutionContext.empty()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(Files.isRegularFile(directory))
        .as("the storage directory must never be replaced by a file")
        .isFalse();
  }

  /**
   * REGRESSION (area survived): the documented traversal shapes are rejected before any I/O.
   */
  @Test
  void traversalAndSeparatorFileNamesAreRejectedBeforeAnyWrite() {
    Path directory = tempDir.resolve("bundles");
    FilesystemBundleDestination destination = new FilesystemBundleDestination(directory);

    for (String name : new String[] {"../escape.pdf", "..", ".", "a/b.pdf", "a\\b.pdf", "/etc/passwd"}) {
      assertThatThrownBy(
          () -> destination.store(artifact(name, FIRST_BUNDLE), BundleExecutionContext.empty()))
          .as("file name '%s'", name)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(directory).doesNotExist();
  }

  /**
   * DOCUMENTED GAP: a pre-existing directory with loose permissions is reused as-is —
   * createDirectories only applies owner-only permissions to directories it creates itself, so
   * pointing the destination at an existing world-readable directory silently stores legal
   * documents world-readably (the file itself is chmod-ed, but the finding above shows when).
   */
  @Test
  void aPreExistingWorldReadableDirectoryIsNotTightened() throws Exception {
    Path directory = tempDir.resolve("bundles");
    assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Files.createDirectories(directory);
    Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
    FilesystemBundleDestination destination = new FilesystemBundleDestination(directory);

    destination.store(artifact("hearing-bundle.pdf", FIRST_BUNDLE), BundleExecutionContext.empty());

    // Passes today: asserts the current (loose) behaviour so a future tightening shows up.
    assertThat(Files.getPosixFilePermissions(directory))
        .contains(PosixFilePermission.OTHERS_READ);
  }

  private static BundleArtifact artifact(String fileName, byte[] bytes) {
    return artifactWithStream(fileName, new ByteArrayInputStream(bytes));
  }

  private static BundleArtifact artifactWithStream(String fileName, InputStream stream) {
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
        return SECOND_BUNDLE.length;
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
        return stream;
      }
    };
  }
}
