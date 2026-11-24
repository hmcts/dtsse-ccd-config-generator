package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * An image centred on the pages of each document (not on pages the toolkit generates, such as
 * the contents). By default it is drawn over every page's content.
 *
 * <pre>{@code
 * Watermark.image(draftPng).onFirstPageOnly().underContent()
 * }</pre>
 *
 * <p>The image is held in memory, so a request carrying a watermark can be queued and rendered
 * by another instance.
 */
public final class Watermark {
  private final byte[] image;
  private final boolean firstPageOnly;
  private final boolean behindContent;

  private Watermark(byte[] image, boolean firstPageOnly, boolean behindContent) {
    this.image = image;
    this.firstPageOnly = firstPageOnly;
    this.behindContent = behindContent;
  }

  /** This PNG or JPEG over every page. */
  public static Watermark image(Path image) {
    Validate.requireNonNull(image, "Watermark.image");
    if (!Files.isRegularFile(image)) {
      throw new IllegalArgumentException("Watermark image " + image + " is not a readable file");
    }
    try {
      return image(Files.readAllBytes(image));
    } catch (IOException e) {
      throw new UncheckedIOException("Watermark image " + image + " could not be read", e);
    }
  }

  /** This PNG or JPEG over every page. */
  public static Watermark image(byte[] image) {
    Validate.requireNonNull(image, "Watermark.image");
    if (image.length == 0) {
      throw new IllegalArgumentException("Watermark image is empty");
    }
    return new Watermark(image.clone(), false, false);
  }

  /** The image. */
  public byte[] image() {
    return image.clone();
  }

  /** The same watermark on each document's first page only. */
  public Watermark onFirstPageOnly() {
    return new Watermark(image, true, behindContent);
  }

  /** The same watermark drawn under the page's content. */
  public Watermark underContent() {
    return new Watermark(image, firstPageOnly, true);
  }

  /** Whether only each document's first page is marked. */
  public boolean firstPageOnly() {
    return firstPageOnly;
  }

  /** Whether the image is drawn under the page's content rather than over it. */
  public boolean behindContent() {
    return behindContent;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Watermark that && firstPageOnly == that.firstPageOnly
        && behindContent == that.behindContent && Arrays.equals(image, that.image);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(image) + (firstPageOnly ? 2 : 0) + (behindContent ? 1 : 0);
  }
}
