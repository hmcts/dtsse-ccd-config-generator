package uk.gov.hmcts.ccd.sdk.conversion;

import java.util.Objects;

/**
 * How {@link DocumentConverter#toPrintReadyPdf} lays out and encodes its output.
 *
 * <p>Every source page is rendered to an image at {@code resolutionDpi} and placed, scaled to
 * fit and centred, inside the printable area: the page less {@code marginMillimetres} on every
 * side. Nothing is drawn in the margin. Images are never enlarged past one pixel per point, and
 * are downsampled when they would print denser than {@code resolutionDpi}, so no image in the
 * output exceeds it.
 *
 * @param pageSize the output paper size
 * @param marginMillimetres the blank band kept on every edge
 * @param resolutionDpi the maximum resolution of any image in the output, and the resolution
 *     document pages are rendered at
 * @param colour the output colour space
 * @param standard the PDF standard the output conforms to
 * @param outputCondition the printing condition named in the PDF/X output intent
 * @param rotateLandscapePages whether a landscape source page is turned a quarter
 *     counter-clockwise onto the portrait output page, so it prints larger; its top lands on the
 *     left edge, as a printer driver turns landscape
 * @param padToEvenPages whether a blank page is added to an odd page count, so the output is
 *     ready for duplex printing; send-letter re-saves any odd-page document, which would undo
 *     PDF/X-1a, so bulk print needs this on
 * @param maxOutputBytes the largest output accepted, or 0 for no limit; above it conversion
 *     fails with {@code OUTPUT_TOO_LARGE}
 * @param greyscaleAboveFraction when CMYK output exceeds this fraction of {@code maxOutputBytes},
 *     it is converted again in greyscale, which is roughly half the size or less
 */
public record PrintOptions(
    PageSize pageSize,
    double marginMillimetres,
    int resolutionDpi,
    PrintColour colour,
    PdfStandard standard,
    OutputCondition outputCondition,
    boolean rotateLandscapePages,
    boolean padToEvenPages,
    long maxOutputBytes,
    double greyscaleAboveFraction) {

  /** The lowest accepted resolution. */
  public static final int MIN_RESOLUTION_DPI = 72;

  /** The highest accepted resolution. */
  public static final int MAX_RESOLUTION_DPI = 600;

  /** The default size limit for bulk print: 20 MiB. */
  public static final long DEFAULT_MAX_OUTPUT_BYTES = 20L * 1024 * 1024;

  /** The smallest printable area accepted, in millimetres, on either axis. */
  private static final double MIN_PRINTABLE_MILLIMETRES = 50;

  /** Validates the options. */
  public PrintOptions {
    Objects.requireNonNull(pageSize, "pageSize must be provided");
    Objects.requireNonNull(colour, "colour must be provided");
    Objects.requireNonNull(standard, "standard must be provided");
    Objects.requireNonNull(outputCondition, "outputCondition must be provided");
    if (!(marginMillimetres >= 0)) {
      throw new IllegalArgumentException(
          "marginMillimetres must be zero or more, was " + marginMillimetres);
    }
    double narrowest = Math.min(pageSize.widthMillimetres(), pageSize.heightMillimetres());
    if (narrowest - 2 * marginMillimetres < MIN_PRINTABLE_MILLIMETRES) {
      throw new IllegalArgumentException(
          "A " + marginMillimetres + "mm margin leaves less than " + MIN_PRINTABLE_MILLIMETRES
              + "mm to print on a " + pageSize + " page");
    }
    if (resolutionDpi < MIN_RESOLUTION_DPI || resolutionDpi > MAX_RESOLUTION_DPI) {
      throw new IllegalArgumentException(
          "resolutionDpi must be between " + MIN_RESOLUTION_DPI + " and " + MAX_RESOLUTION_DPI
              + ", was " + resolutionDpi);
    }
    if (maxOutputBytes < 0) {
      throw new IllegalArgumentException(
          "maxOutputBytes must be zero (no limit) or more, was " + maxOutputBytes);
    }
    if (!(greyscaleAboveFraction > 0 && greyscaleAboveFraction <= 1)) {
      throw new IllegalArgumentException(
          "greyscaleAboveFraction must be above 0 and at most 1, was " + greyscaleAboveFraction);
    }
  }

  /** The output size above which CMYK output is redone in greyscale; 0 when there is no limit. */
  long greyscaleThresholdBytes() {
    return maxOutputBytes == 0 ? 0 : (long) (maxOutputBytes * greyscaleAboveFraction);
  }

  /**
   * The bulk print (Xerox) profile: A4, PDF/X-1a:2001, CMYK, 300 dpi, a 20 mm margin, landscape
   * pages turned to portrait, padded to an even page count, at most 20 MiB, and greyscale above
   * 80% of that.
   */
  public static PrintOptions bulkPrint() {
    return builder().build();
  }

  /**
   * A builder starting from the {@link #bulkPrint()} values.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** A builder starting from these options. */
  public Builder toBuilder() {
    return new Builder()
        .pageSize(pageSize)
        .marginMillimetres(marginMillimetres)
        .resolutionDpi(resolutionDpi)
        .colour(colour)
        .standard(standard)
        .outputCondition(outputCondition)
        .rotateLandscapePages(rotateLandscapePages)
        .padToEvenPages(padToEvenPages)
        .maxOutputBytes(maxOutputBytes)
        .greyscaleAboveFraction(greyscaleAboveFraction);
  }

  /**
   * Builds {@link PrintOptions}. Unset values take the {@link #bulkPrint()} defaults.
   */
  public static final class Builder {
    private PageSize pageSize = PageSize.A4;
    private double marginMillimetres = 20;
    private int resolutionDpi = 300;
    private PrintColour colour = PrintColour.CMYK;
    private PdfStandard standard = PdfStandard.PDF_X_1A_2001;
    private OutputCondition outputCondition = OutputCondition.FOGRA39;
    private boolean rotateLandscapePages = true;
    private boolean padToEvenPages = true;
    private long maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
    private double greyscaleAboveFraction = 0.8;

    private Builder() {
    }

    /** The output paper size. Default A4. */
    public Builder pageSize(PageSize pageSize) {
      this.pageSize = pageSize;
      return this;
    }

    /** The blank band kept on every edge. Default 20. */
    public Builder marginMillimetres(double marginMillimetres) {
      this.marginMillimetres = marginMillimetres;
      return this;
    }

    /** The maximum image resolution. Default 300; 150 roughly quarters the file size. */
    public Builder resolutionDpi(int resolutionDpi) {
      this.resolutionDpi = resolutionDpi;
      return this;
    }

    /** The output colour space. Default CMYK. */
    public Builder colour(PrintColour colour) {
      this.colour = colour;
      return this;
    }

    /** The PDF standard. Default PDF/X-1a:2001. */
    public Builder standard(PdfStandard standard) {
      this.standard = standard;
      return this;
    }

    /** The PDF/X printing condition. Default FOGRA39. */
    public Builder outputCondition(OutputCondition outputCondition) {
      this.outputCondition = outputCondition;
      return this;
    }

    /** Whether landscape pages are turned onto portrait paper. Default true. */
    public Builder rotateLandscapePages(boolean rotateLandscapePages) {
      this.rotateLandscapePages = rotateLandscapePages;
      return this;
    }

    /** Whether odd page counts get a blank page. Default true. */
    public Builder padToEvenPages(boolean padToEvenPages) {
      this.padToEvenPages = padToEvenPages;
      return this;
    }

    /** The largest output accepted; 0 for no limit. Default 20 MiB. */
    public Builder maxOutputBytes(long maxOutputBytes) {
      this.maxOutputBytes = maxOutputBytes;
      return this;
    }

    /**
     * The fraction of {@code maxOutputBytes} above which CMYK becomes greyscale. Default 0.8.
     */
    public Builder greyscaleAboveFraction(double greyscaleAboveFraction) {
      this.greyscaleAboveFraction = greyscaleAboveFraction;
      return this;
    }

    /** Builds and validates the options. */
    public PrintOptions build() {
      return new PrintOptions(pageSize, marginMillimetres, resolutionDpi, colour, standard,
          outputCondition, rotateLandscapePages, padToEvenPages, maxOutputBytes,
          greyscaleAboveFraction);
    }
  }
}
