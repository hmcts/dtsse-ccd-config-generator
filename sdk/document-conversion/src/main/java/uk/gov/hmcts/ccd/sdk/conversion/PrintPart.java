package uk.gov.hmcts.ccd.sdk.conversion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One part of a print-ready letter, in the order it is printed: see
 * {@link DocumentConverter#toPrintReadyLetter}.
 */
public sealed interface PrintPart {

  /**
   * A PDF whose pages go into the letter unchanged, such as an address coversheet whose layout
   * is fixed by the envelope window. Its pages are not re-rendered, so they keep their own
   * colour, fonts and margins; only a missing trim box is added.
   */
  static PrintPart asIs(Path pdf, String name) {
    return new AsIs(pdf, name);
  }

  /** A document converted to print-ready pages. */
  static PrintPart convert(ConversionSource source) {
    return new Convert(source);
  }

  /**
   * See {@link #asIs}.
   */
  record AsIs(Path pdf, String name) implements PrintPart {
    /** Validates the part. */
    public AsIs {
      Objects.requireNonNull(pdf, "pdf must be provided");
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("name must be provided");
      }
      if (!Files.isRegularFile(pdf)) {
        throw new IllegalArgumentException("PDF " + pdf + " is not a readable file");
      }
    }
  }

  /**
   * See {@link #convert}.
   */
  record Convert(ConversionSource source) implements PrintPart {
    /** Validates the part. */
    public Convert {
      Objects.requireNonNull(source, "source must be provided");
    }
  }
}
