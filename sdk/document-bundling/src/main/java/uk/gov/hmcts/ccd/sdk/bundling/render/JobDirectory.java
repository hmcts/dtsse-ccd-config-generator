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

final class JobDirectory {
  private static final Logger log = LoggerFactory.getLogger(JobDirectory.class);
  private static final Set<PosixFilePermission> OWNER_ONLY_DIR =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
      PosixFilePermissions.fromString("rw-------");

  private JobDirectory() {
  }

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

  static Path createFile(Path directory, String suffix) throws IOException {
    if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      FileAttribute<Set<PosixFilePermission>> ownerOnly =
          PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE);
      return Files.createTempFile(directory, "bundling-", suffix, ownerOnly);
    }
    return Files.createTempFile(directory, "bundling-", suffix);
  }

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
