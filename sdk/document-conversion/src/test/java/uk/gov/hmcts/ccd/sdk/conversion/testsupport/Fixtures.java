package uk.gov.hmcts.ccd.sdk.conversion.testsupport;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

/**
 * Deterministic input documents. Images are drawn with shapes only and PDF text uses the
 * Liberation Sans font bundled in PDFBox, embedded, so the inputs and therefore the goldens are
 * identical on every platform.
 */
public final class Fixtures {
  private static final float A4_WIDTH = PDRectangle.A4.getWidth();
  private static final float A4_HEIGHT = PDRectangle.A4.getHeight();

  private Fixtures() {
  }

  /** Copies a committed fixture into {@code dir}. */
  public static Path real(Path dir, String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("No fixture " + name);
      }
      Path target = dir.resolve(name);
      Files.copy(in, target);
      return target;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Three pages that each break a print rule: a portrait page whose coloured background runs to
   * every edge, a landscape page, and a portrait page stored with {@code /Rotate 90}. RGB colour,
   * PDF 1.4.
   */
  public static Path mixedLayoutPdf(Path dir) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDFont font = font(document);

      PDPage bleed = new PDPage(PDRectangle.A4);
      document.addPage(bleed);
      try (PDPageContentStream c = new PDPageContentStream(document, bleed)) {
        fill(c, 0.85f, 0.92f, 1f, 0, 0, A4_WIDTH, A4_HEIGHT);
        fill(c, 0.8f, 0.1f, 0.1f, 0, A4_HEIGHT - 60, A4_WIDTH, 60);
        text(c, font, 20, 1, 1, 1, 30, A4_HEIGHT - 40, "Claim for possession: bleed to edge");
        for (int line = 0; line < 30; line++) {
          text(c, font, 11, 0, 0, 0, 30, A4_HEIGHT - 100 - line * 22,
              "Line " + (line + 1) + " of body text running right up to the page edge "
                  + "...............................................");
        }
        table(c, 30, 60, A4_WIDTH - 60, 80, 4, 3);
      }

      PDPage landscape = new PDPage(new PDRectangle(A4_HEIGHT, A4_WIDTH));
      document.addPage(landscape);
      try (PDPageContentStream c = new PDPageContentStream(document, landscape)) {
        text(c, font, 18, 0, 0, 0, 40, A4_WIDTH - 50, "Rent arrears schedule (landscape)");
        float[][] colours = {{0.9f, 0.2f, 0.2f}, {0.2f, 0.6f, 0.2f}, {0.2f, 0.3f, 0.9f},
            {0.95f, 0.75f, 0.1f}};
        for (int bar = 0; bar < 12; bar++) {
          float[] rgb = colours[bar % colours.length];
          fill(c, rgb[0], rgb[1], rgb[2], 60 + bar * 60, 60, 40, 40 + bar * 30);
        }
        table(c, 40, 20, A4_HEIGHT - 80, 30, 1, 12);
      }

      PDPage rotated = new PDPage(PDRectangle.A4);
      rotated.setRotation(90);
      document.addPage(rotated);
      try (PDPageContentStream c = new PDPageContentStream(document, rotated)) {
        fill(c, 0.1f, 0.5f, 0.3f, 50, 50, 200, 300);
        text(c, font, 16, 0, 0, 0, 50, A4_HEIGHT - 60, "Stored portrait, displayed landscape");
      }
      return save(document, dir.resolve("mixed-layout.pdf"));
    }
  }

  /**
   * A page using transparency, which PDF/X-1a forbids: overlapping half-opaque fills through an
   * extended graphics state, and a PNG with an alpha channel (an SMask).
   */
  public static Path transparencyPdf(Path dir) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      document.addPage(page);
      PDExtendedGraphicsState halfOpaque = new PDExtendedGraphicsState();
      halfOpaque.setNonStrokingAlphaConstant(0.5f);
      try (PDPageContentStream c = new PDPageContentStream(document, page)) {
        c.saveGraphicsState();
        c.setGraphicsStateParameters(halfOpaque);
        fill(c, 1, 0, 0, 100, 400, 250, 250);
        fill(c, 0, 0, 1, 250, 300, 250, 250);
        c.restoreGraphicsState();
        c.drawImage(LosslessFactory.createFromImage(document, alphaImage(400, 300)),
            100, 50, 400, 300);
        text(c, font(document), 14, 0, 0, 0, 100, 760, "Transparency must be flattened");
      }
      return save(document, dir.resolve("transparency.pdf"));
    }
  }

  /** A three-frame TIFF: a portrait scan at 150 dpi, a landscape scan, and a tiny frame. */
  public static Path multiPageTiff(Path dir) throws IOException {
    Path file = dir.resolve("scans.tiff");
    List<BufferedImage> frames = List.of(
        scan(1240, 1754, new Color(30, 30, 30)),
        scan(1754, 1240, new Color(20, 40, 120)),
        scan(200, 100, new Color(120, 20, 20)));
    ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
    try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
      writer.setOutput(out);
      writer.prepareWriteSequence(null);
      for (BufferedImage frame : frames) {
        writer.writeToSequence(new IIOImage(frame, null, null), null);
      }
      writer.endWriteSequence();
    } finally {
      writer.dispose();
    }
    return file;
  }

  /** A PNG with a fully transparent background, which must print as white. */
  public static Path alphaPng(Path dir) throws IOException {
    return write(alphaImage(800, 600), "png", dir.resolve("alpha.png"));
  }

  /** A 24-bit BMP. */
  public static Path bmp(Path dir) throws IOException {
    return write(scan(640, 480, new Color(0, 90, 60)), "bmp", dir.resolve("photo.bmp"));
  }

  /** A 24 megapixel landscape JPEG: printed at 300 dpi it must be downsampled about threefold. */
  public static Path largePhoto(Path dir) throws IOException {
    BufferedImage image = new BufferedImage(6000, 4000, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setPaint(new GradientPaint(0, 0, new Color(250, 200, 120), 6000, 4000,
          new Color(40, 90, 160)));
      g.fillRect(0, 0, 6000, 4000);
      g.setColor(Color.BLACK);
      g.setStroke(new BasicStroke(12));
      for (int i = 0; i < 40; i++) {
        g.drawLine(i * 150, 0, i * 150, 4000);
      }
      g.setColor(Color.WHITE);
      g.fillOval(2000, 1000, 2000, 2000);
    } finally {
      g.dispose();
    }
    return write(image, "jpeg", dir.resolve("large-photo.jpg"));
  }

  /** A PDF that opens only with a password. */
  public static Path encryptedPdf(Path dir) throws IOException {
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage(PDRectangle.A4));
      StandardProtectionPolicy policy =
          new StandardProtectionPolicy("owner", "user", new AccessPermission());
      policy.setEncryptionKeyLength(128);
      document.protect(policy);
      return save(document, dir.resolve("encrypted.pdf"));
    }
  }

  /** A ZIP archive: an OLE2/ZIP-family container that is not an office document. */
  public static Path zip(Path dir) throws IOException {
    Path file = dir.resolve("archive.zip");
    try (OutputStream out = Files.newOutputStream(file);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("readme.txt"));
      zip.write("not a document".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return file;
  }

  /** A text file. */
  public static Path text(Path dir, String name, String content) throws IOException {
    return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
  }

  /** A one-page A4 PDF saying {@code message}, as a stub Docmosis might return. */
  public static byte[] pdfSaying(String message) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      document.addPage(page);
      try (PDPageContentStream c = new PDPageContentStream(document, page)) {
        text(c, font(document), 14, 0, 0, 0, 72, A4_HEIGHT - 72, message);
      }
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }

  private static BufferedImage alphaImage(int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
      g.setColor(new Color(200, 30, 120));
      g.fillOval(width / 8, height / 8, width * 3 / 4, height * 3 / 4);
      g.setColor(new Color(0, 0, 0, 128));
      g.fillRect(width / 4, height / 2, width / 2, height / 6);
    } finally {
      g.dispose();
    }
    return image;
  }

  /** Stripes standing in for lines of text, inside a border, on white. */
  private static BufferedImage scan(int width, int height, Color ink) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, width, height);
      g.setColor(ink);
      int pad = Math.max(2, width / 20);
      g.setStroke(new BasicStroke(Math.max(1, width / 200f)));
      g.drawRect(pad, pad, width - 2 * pad, height - 2 * pad);
      int lineHeight = Math.max(4, height / 40);
      for (int y = pad * 2; y + lineHeight < height - pad * 2; y += lineHeight * 2) {
        int length = (width - 4 * pad) * (60 + (y * 7) % 40) / 100;
        g.fillRect(pad * 2, y, length, Math.max(1, lineHeight / 2));
      }
    } finally {
      g.dispose();
    }
    return image;
  }

  private static PDFont font(PDDocument document) throws IOException {
    try (InputStream in = PDDocument.class.getResourceAsStream(
        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
      return PDType0Font.load(document, in);
    }
  }

  private static void fill(PDPageContentStream c, float r, float g, float b, float x, float y,
      float width, float height) throws IOException {
    c.setNonStrokingColor(r, g, b);
    c.addRect(x, y, width, height);
    c.fill();
  }

  private static void text(PDPageContentStream c, PDFont font, float size, float r, float g,
      float b, float x, float y, String text) throws IOException {
    c.beginText();
    c.setNonStrokingColor(r, g, b);
    c.setFont(font, size);
    c.newLineAtOffset(x, y);
    c.showText(text);
    c.endText();
  }

  private static void table(PDPageContentStream c, float x, float y, float width, float height,
      int rows, int columns) throws IOException {
    c.setStrokingColor(0f, 0f, 0f);
    c.setLineWidth(0.8f);
    for (int row = 0; row <= rows; row++) {
      c.moveTo(x, y + row * height / rows);
      c.lineTo(x + width, y + row * height / rows);
    }
    for (int column = 0; column <= columns; column++) {
      c.moveTo(x + column * width / columns, y);
      c.lineTo(x + column * width / columns, y + height);
    }
    c.stroke();
  }

  private static Path write(BufferedImage image, String format, Path file) throws IOException {
    if (!ImageIO.write(image, format, file.toFile())) {
      throw new IOException("No ImageIO writer for " + format);
    }
    return file;
  }

  private static Path save(PDDocument document, Path file) throws IOException {
    document.save(file.toFile());
    return file;
  }
}
