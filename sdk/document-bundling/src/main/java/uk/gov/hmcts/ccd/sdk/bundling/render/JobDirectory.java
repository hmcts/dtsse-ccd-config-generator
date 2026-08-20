package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Job-scoped temporary directories: owner-only, per job, guaranteed cleanup. Every spooled
 * source, handler temp file, PDFBox spill file, and the assembled output live under one job
 * directory, and one recursive delete in the renderer's {@code finally} removes them all —
 * success, failure, and timeout alike.
 */
final class JobDirectory {

  private static final Logger log = LoggerFactory.getLogger(JobDirectory.class);
  private static final Set<PosixFilePermission> OWNER_ONLY_DIR =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
      PosixFilePermissions.fromString("rw-------");

  private JobDirectory() {
  }

  /**
   * Creates the job's temporary directory under the configured base (or {@code java.io.tmpdir}),
   * owner-only where the filesystem supports POSIX permissions.
   *
   * @param base the configured base directory, or null for {@code java.io.tmpdir}
   * @param externalId the job's external id, part of the directory name for diagnosability
   * @return the created directory
   * @throws IOException if the directory cannot be created
   */
  static Path create(Path base, UUID externalId) throws IOException {
    Path parent = base != null ? base : Path.of(System.getProperty("java.io.tmpdir"));
    Files.createDirectories(parent);
    String prefix = "ccd-bundling-" + externalId + "-";
    if (parent.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return Files.createTempDirectory(parent, prefix,
          PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
    }
    return Files.createTempDirectory(parent, prefix);
  }

  /**
   * Creates an owner-only temporary file inside a job directory.
   *
   * @param directory the job directory
   * @param suffix the file name suffix
   * @return the created file
   * @throws IOException if the file cannot be created
   */
  static Path createFile(Path directory, String suffix) throws IOException {
    if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      FileAttribute<Set<PosixFilePermission>> ownerOnly =
          PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE);
      return Files.createTempFile(directory, "bundling-", suffix, ownerOnly);
    }
    return Files.createTempFile(directory, "bundling-", suffix);
  }

  /**
   * Deletes a job directory and everything under it, best-effort: cleanup failure must never
   * mask the render outcome, so problems are logged and swallowed.
   *
   * @param directory the job directory, or null when creation never happened
   */
  static void deleteRecursively(Path directory) {
    if (directory == null) {
      return;
    }
    try {
      Files.walkFileTree(directory, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            throws IOException {
          Files.deleteIfExists(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          if (exc != null) {
            throw exc;
          }
          Files.deleteIfExists(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      log.warn("Could not fully delete the job temporary directory {}: {}",
          directory, e.toString());
    }
  }
}
