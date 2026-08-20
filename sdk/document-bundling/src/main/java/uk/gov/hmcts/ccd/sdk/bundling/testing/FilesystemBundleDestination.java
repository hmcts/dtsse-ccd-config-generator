package uk.gov.hmcts.ccd.sdk.bundling.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDestination;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.StoredBundle;

/**
 * A {@link BundleDestination} that stores the finished bundle on the local filesystem, for tests
 * and local runs only.
 *
 * <p>Production artifact storage is invariant — always CDAM, via
 * {@link uk.gov.hmcts.ccd.sdk.bundling.cdam.CdamBundleDestination}. This destination exists as
 * the test seam the {@code BundleDestination} port was kept for: it lets a consumer exercise the
 * renderer end to end without a CDAM instance. Never wire it into a deployed service.
 *
 * <p>Publication is atomic even here: the artifact is written to a temporary file in the same
 * directory and atomically renamed over the target, so a stream that fails mid-write never
 * destroys or truncates the previously stored bundle. The configured directory is created on
 * demand with owner-only permissions, and each file is created with owner-only permissions
 * before any byte is written, on filesystems that support POSIX permissions. The returned
 * {@link StoredBundle} carries {@code file:} URIs for both links and no hash token.
 */
public final class FilesystemBundleDestination implements BundleDestination {

  private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");

  private final Path directory;

  /**
   * Creates the destination.
   *
   * @param directory the directory stored bundles are written into; created on demand
   */
  public FilesystemBundleDestination(Path directory) {
    if (directory == null) {
      throw new IllegalArgumentException("FilesystemBundleDestination.directory must be provided");
    }
    this.directory = directory;
  }

  @Override
  public StoredBundle store(BundleArtifact artifact, BundleExecutionContext context) {
    String fileName = safeFileName(artifact.fileName());
    Path target = directory.resolve(fileName);
    Path temporary = null;
    try {
      createDirectory();
      // Owner-only from the moment of creation, then written, then atomically renamed into
      // place: no partially written or loosely permissioned bundle is ever visible at target.
      temporary = Files.createTempFile(directory, "." + fileName + "-", ".tmp", fileAttributes());
      try (InputStream in = artifact.open();
          OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING)) {
        in.transferTo(out);
      }
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      deleteQuietly(temporary);
      throw new UncheckedIOException(
          "Could not store bundle '" + fileName + "' under " + directory, e);
    }
    String url = target.toUri().toString();
    return new StoredBundle(
        url, url, artifact.fileName(), artifact.mediaType(), artifact.size(), artifact.sha256(),
        Optional.empty());
  }

  private void createDirectory() throws IOException {
    if (posixSupported()) {
      FileAttribute<Set<PosixFilePermission>> ownerOnly =
          PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS);
      Files.createDirectories(directory, ownerOnly);
    } else {
      Files.createDirectories(directory);
    }
  }

  private FileAttribute<?>[] fileAttributes() {
    return posixSupported()
        ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS)}
        : new FileAttribute<?>[0];
  }

  private boolean posixSupported() {
    return directory.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  private static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      // Best effort: leftover temp files carry owner-only permissions and a .tmp suffix.
    }
  }

  private static String safeFileName(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      throw new IllegalArgumentException("Bundle file name must be provided and non-blank");
    }
    if (fileName.contains("/") || fileName.contains("\\")
        || fileName.equals(".") || fileName.equals("..")) {
      throw new IllegalArgumentException(
          "Bundle file name '" + fileName + "' must be a plain file name without path segments");
    }
    return fileName;
  }
}
