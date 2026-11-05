package uk.gov.hmcts.ccd.sdk.conversion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A file to convert. The converter never modifies or deletes it.
 *
 * @param file the content on local disk
 * @param fileName the original file name, used for Docmosis and to infer a missing media type
 * @param mediaType the declared media type, or null or blank when unknown; it is checked against
 *     the content, and content that is recognisably another type is converted as that type
 */
public record ConversionSource(Path file, String fileName, String mediaType) {

  /** Validates the source. */
  public ConversionSource {
    Objects.requireNonNull(file, "file must be provided");
    if (fileName == null || fileName.isBlank()) {
      throw new IllegalArgumentException("fileName must be provided");
    }
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("Source file " + file + " is not a readable file");
    }
  }

  /** A source with a declared media type. */
  public static ConversionSource of(Path file, String fileName, String mediaType) {
    return new ConversionSource(file, fileName, mediaType);
  }

  /** A source whose media type is inferred from its content and file name. */
  public static ConversionSource of(Path file, String fileName) {
    return new ConversionSource(file, fileName, null);
  }
}
