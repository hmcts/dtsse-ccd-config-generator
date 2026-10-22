package uk.gov.hmcts.ccd.sdk.conversion;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;

/**
 * Builds print-ready PDFs. Every output page carries exactly one opaque image in the printable
 * area and nothing else, which is what makes the output's guarantees hold for any source: no
 * fonts to embed, no RGB or ICC colour, no transparency, no content in the margin, and no image
 * denser than the configured resolution.
 */
final class PrintRenderer {
  static final float POINTS_PER_INCH = 72f;
  static final float POINTS_PER_MILLIMETRE = POINTS_PER_INCH / 25.4f;

  private final PrintOptions options;
  private final Path scratchDirectory;
  private final PDRectangle pageBox;

  PrintRenderer(PrintOptions options, Path scratchDirectory) {
    this.options = options;
    this.scratchDirectory = scratchDirectory;
    this.pageBox = new PDRectangle(
        (float) (options.pageSize().widthMillimetres() * POINTS_PER_MILLIMETRE),
        (float) (options.pageSize().heightMillimetres() * POINTS_PER_MILLIMETRE));
  }

  /** Lays out each frame of an image file; returns the page count. */
  int renderImage(Path image, String title, Path output) throws IOException {
    try (PDDocument out = newDocument()) {
      ImageFrames.read(image,
          (width, height) -> place(width, height, true).subsamplingFor(width, height),
          (frame, fullWidth, fullHeight) ->
              addPage(out, frame, place(fullWidth, fullHeight, true)));
      return save(out, title, output);
    }
  }

  /** Renders each page of a PDF; returns the page count. */
  int renderPdf(Path pdf, String title, Path output) throws IOException {
    try (PDDocument source = PdfChecks.load(pdf, scratchDirectory);
        PDDocument out = newDocument()) {
      PDFRenderer renderer = new PDFRenderer(source);
      renderer.setSubsamplingAllowed(false);
      for (int i = 0; i < source.getNumberOfPages(); i++) {
        PDPage page = source.getPage(i);
        PDRectangle crop = page.getCropBox();
        boolean quarterTurned = page.getRotation() % 180 != 0;
        float width = quarterTurned ? crop.getHeight() : crop.getWidth();
        float height = quarterTurned ? crop.getWidth() : crop.getHeight();
        Placement placement = place(width, height, false);
        // Render straight to the pixel count the placement prints at, so nothing is resampled.
        int pixelsAcrossSourceWidth =
            placement.rotate() ? placement.pixelHeight() : placement.pixelWidth();
        float scale = pixelsAcrossSourceWidth / width;
        BufferedImage rendered =
            renderer.renderImage(i, scale, ImageType.RGB, RenderDestination.PRINT);
        addPage(out, rendered, placement);
      }
      return save(out, title, output);
    }
  }

  private PDDocument newDocument() {
    return new PDDocument(MemoryUsageSetting.setupMixed(PdfChecks.MAX_CACHE_HEAP_BYTES)
        .setTempDir(scratchDirectory.toFile()).streamCache);
  }

  private int save(PDDocument out, String title, Path output) throws IOException {
    if (options.standard() == PdfStandard.PDF_X_1A_2001) {
      PdfX1a.identify(out, title, options.outputCondition());
      PdfX1a.save(out, output);
    } else {
      out.getDocumentInformation().setTitle(title);
      out.save(output.toFile());
    }
    return out.getNumberOfPages();
  }

  /**
   * Fits content of the given natural size, in points, into the printable area.
   *
   * @param neverEnlarge whether the content may only be shrunk, for images whose natural size is
   *     one pixel per point
   */
  Placement place(float width, float height, boolean neverEnlarge) {
    boolean rotate = options.rotateLandscapePages() && width > height
        && pageBox.getWidth() < pageBox.getHeight();
    float contentWidth = rotate ? height : width;
    float contentHeight = rotate ? width : height;
    float margin = (float) (options.marginMillimetres() * POINTS_PER_MILLIMETRE);
    float printableWidth = pageBox.getWidth() - 2 * margin;
    float printableHeight = pageBox.getHeight() - 2 * margin;
    float scale = Math.min(printableWidth / contentWidth, printableHeight / contentHeight);
    if (neverEnlarge) {
      scale = Math.min(scale, 1f);
    }
    float placedWidth = Math.min(contentWidth * scale, printableWidth);
    float placedHeight = Math.min(contentHeight * scale, printableHeight);
    int dpi = options.resolutionDpi();
    return new Placement(rotate,
        (pageBox.getWidth() - placedWidth) / 2,
        (pageBox.getHeight() - placedHeight) / 2,
        placedWidth,
        placedHeight,
        Math.max(1, (int) Math.floor(placedWidth / POINTS_PER_INCH * dpi)),
        Math.max(1, (int) Math.floor(placedHeight / POINTS_PER_INCH * dpi)));
  }

  private void addPage(PDDocument out, BufferedImage source, Placement placement)
      throws IOException {
    BufferedImage prepared = prepare(source, placement);
    Encoded encoded = encode(out, prepared);
    PDPage page = new PDPage(new PDRectangle(pageBox.getWidth(), pageBox.getHeight()));
    page.setTrimBox(new PDRectangle(pageBox.getWidth(), pageBox.getHeight()));
    out.addPage(page);
    try (PDPageContentStream contents = new PDPageContentStream(out, page)) {
      // Name the image's content in the page's own content stream. Send-letter detects
      // duplicate letters by hashing page content streams alone, and without this every page of
      // the same layout would hash the same whatever its image showed.
      contents.addComment("image sha256 " + encoded.sha256());
      contents.drawImage(encoded.image(), placement.x(), placement.y(), placement.width(),
          placement.height());
    }
  }

  /**
   * Flattens onto white, applies the quarter turn, and downsamples to the placement's pixel
   * count when the image is denser than the resolution allows. Smaller images are left alone.
   */
  private static BufferedImage prepare(BufferedImage source, Placement placement) {
    int width = placement.rotate() ? source.getHeight() : source.getWidth();
    int height = placement.rotate() ? source.getWidth() : source.getHeight();
    BufferedImage upright = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = upright.createGraphics();
    try {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, width, height);
      if (placement.rotate()) {
        // Counter-clockwise, as printer drivers turn landscape: the top goes to the left edge.
        g.translate(0, height);
        g.rotate(-Math.PI / 2);
      }
      g.drawImage(source, 0, 0, null);
    } finally {
      g.dispose();
    }
    if (width <= placement.pixelWidth() && height <= placement.pixelHeight()) {
      return upright;
    }
    return downsample(upright, placement.pixelWidth(), placement.pixelHeight());
  }

  /** Halves repeatedly, then scales the remainder bicubically, which avoids aliasing. */
  private static BufferedImage downsample(BufferedImage image, int width, int height) {
    BufferedImage current = image;
    while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
      current = scale(current, current.getWidth() / 2, current.getHeight() / 2,
          RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }
    if (current.getWidth() == width && current.getHeight() == height) {
      return current;
    }
    return scale(current, width, height, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
  }

  private static BufferedImage scale(BufferedImage image, int width, int height,
      Object interpolation) {
    BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = scaled.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.drawImage(image, 0, 0, width, height, null);
    } finally {
      g.dispose();
    }
    return scaled;
  }

  /**
   * Separates an opaque RGB image into DeviceCMYK or DeviceGray samples, Flate-compressed. The
   * CMYK separation is the neutral formula: K carries the darkness common to all channels, so
   * black and grey print on the black plate alone.
   */
  private record Encoded(PDImageXObject image, String sha256) {
  }

  private Encoded encode(PDDocument out, BufferedImage image) throws IOException {
    int width = image.getWidth();
    int height = image.getHeight();
    boolean cmyk = options.colour() == PrintColour.CMYK;
    int components = cmyk ? 4 : 1;
    byte[] samples = new byte[width * height * components];
    int[] row = new int[width];
    int offset = 0;
    for (int y = 0; y < height; y++) {
      image.getRGB(0, y, width, 1, row, 0, width);
      for (int x = 0; x < width; x++) {
        int r = (row[x] >> 16) & 0xFF;
        int g = (row[x] >> 8) & 0xFF;
        int b = row[x] & 0xFF;
        if (cmyk) {
          int max = Math.max(r, Math.max(g, b));
          samples[offset++] = (byte) (max == 0 ? 0 : (max - r) * 255 / max);
          samples[offset++] = (byte) (max == 0 ? 0 : (max - g) * 255 / max);
          samples[offset++] = (byte) (max == 0 ? 0 : (max - b) * 255 / max);
          samples[offset++] = (byte) (255 - max);
        } else {
          samples[offset++] = (byte) ((299 * r + 587 * g + 114 * b + 500) / 1000);
        }
      }
    }
    PDStream stream = new PDStream(out, new ByteArrayInputStream(samples), COSName.FLATE_DECODE);
    PDImageXObject encoded = new PDImageXObject(stream, null);
    encoded.setWidth(width);
    encoded.setHeight(height);
    encoded.setBitsPerComponent(8);
    PDColorSpace colorSpace = cmyk ? PDDeviceCMYK.INSTANCE : PDDeviceGray.INSTANCE;
    encoded.setColorSpace(colorSpace);
    return new Encoded(encoded, sha256(samples));
  }

  private static String sha256(byte[] samples) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(samples));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /**
   * Where content goes on the output page, in points from the bottom left, and the pixel size it
   * prints at the configured resolution.
   *
   * @param rotate whether the content is turned a quarter counter-clockwise
   */
  record Placement(boolean rotate, float x, float y, float width, float height,
      int pixelWidth, int pixelHeight) {

    /**
     * The largest whole subsampling factor that still leaves at least the placement's pixel
     * count, so decoding a huge image never holds more pixels than are needed.
     */
    int subsamplingFor(int sourceWidth, int sourceHeight) {
      int across = rotate ? pixelHeight : pixelWidth;
      int down = rotate ? pixelWidth : pixelHeight;
      return Math.max(1, Math.min(sourceWidth / across, sourceHeight / down));
    }
  }
}
