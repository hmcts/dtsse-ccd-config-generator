package uk.gov.hmcts.ccd.sdk.conversion;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WorkDirectory {
  private static final Logger log = LoggerFactory.getLogger(WorkDirectory.class);
  private static final Set<PosixFilePermission> OWNER_ONLY_DIR =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
      PosixFilePermissions.fromString("rw-------");

  private WorkDirectory() {
  }

  static Path create(Path base) throws IOException {
    Files.createDirectories(base);
    if (posix(base)) {
      return Files.createTempDirectory(base, "ccd-conversion-",
          PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
    }
    return Files.createTempDirectory(base, "ccd-conversion-");
  }

  static Path createFile(Path directory, String suffix) throws IOException {
    if (posix(directory)) {
      return Files.createTempFile(directory, "conversion-", suffix,
          PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE));
    }
    return Files.createTempFile(directory, "conversion-", suffix);
  }

  static void deleteRecursively(Path directory) {
    if (directory == null || !Files.exists(directory)) {
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
      log.warn("Could not fully delete the conversion directory {}: {}", directory, e.toString());
    }
  }

  private static boolean posix(Path path) {
    return path.getFileSystem().supportedFileAttributeViews().contains("posix");
  }
}
