package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.nio.file.Path;
import java.util.Optional;

/**
 * An approved watermark preset applied to every source document's pages, ported from
 * {@code em-stitching-api}'s {@code PDFWatermark} (which applies a per-document image overlay
 * with PDFBox {@code Overlay}).
 *
 * <p>Presets are deliberately constrained: the image or text is always centred on the page with
 * fixed styling, and the caller chooses only the page scope and the rendering layer. Free-form
 * coordinates (supported by the current service) are not exposed; a consumer with a concrete
 * legal requirement for them needs a new approved preset, not an escape hatch.
 */
public final class Watermark {

  /**
   * Which pages of each document receive the watermark.
   */
  public enum Scope {

    /** Only the first page of each document. */
    FIRST_PAGE,

    /** Every page of each document. */
    ALL_PAGES
  }

  /**
   * Which layer the watermark is drawn on, mirroring the current service's
   * {@code opaque}/{@code translucent} image rendering options.
   */
  public enum Rendering {

    /**
     * Drawn over the page content (the current service's {@code opaque}).
     */
    OPAQUE,

    /**
     * Drawn behind the page content (the current service's {@code translucent}).
     */
    TRANSLUCENT
  }

  private final Path image;
  private final String text;
  private final Scope scope;
  private final Rendering rendering;

  private Watermark(Path image, String text, Scope scope, Rendering rendering) {
    this.image = image;
    this.text = text;
    this.scope = Checks.requireNonNull(scope, "Watermark.scope");
    this.rendering = Checks.requireNonNull(rendering, "Watermark.rendering");
  }

  /**
   * An image watermark, centred on the page.
   *
   * @param image the image file (PNG or JPEG)
   * @param scope which pages receive the watermark
   * @param rendering which layer the watermark is drawn on
   * @return the preset
   */
  public static Watermark image(Path image, Scope scope, Rendering rendering) {
    Checks.requireNonNull(image, "Watermark.image");
    return new Watermark(image, null, scope, rendering);
  }

  /**
   * The image file, when this is an image watermark.
   *
   * @return the optional image path
   */
  public Optional<Path> image() {
    return Optional.ofNullable(image);
  }

  /**
   * A text watermark, centred on the page in large light-grey type behind the content.
   *
   * @param text the watermark text
   * @param scope which pages receive the watermark
   * @return the preset
   */
  public static Watermark text(String text, Scope scope) {
    Checks.requireNonBlank(text, "Watermark.text");
    return new Watermark(null, text, scope, Rendering.TRANSLUCENT);
  }

  /**
   * The watermark text, when this is a text watermark.
   *
   * @return the optional text
   */
  public Optional<String> text() {
    return Optional.ofNullable(text);
  }

  /**
   * Which pages of each document receive the watermark.
   *
   * @return the page scope
   */
  public Scope scope() {
    return scope;
  }

  /**
   * Which layer the watermark is drawn on.
   *
   * @return the rendering layer
   */
  public Rendering rendering() {
    return rendering;
  }
}
