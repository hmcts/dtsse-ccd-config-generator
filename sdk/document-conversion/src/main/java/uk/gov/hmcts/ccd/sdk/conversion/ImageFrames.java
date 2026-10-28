package uk.gov.hmcts.ccd.sdk.conversion;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntBinaryOperator;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Decodes image files frame by frame. ImageIO reads first, so large images can be subsampled as
 * they are decoded; PDFBox's decoders are the fallback for content ImageIO cannot read, such as
 * CMYK JPEGs. Only TIFFs yield more than one frame: a GIF's later frames are animation, not
 * pages.
 */
final class ImageFrames {

  @FunctionalInterface
  interface FrameConsumer {
    /** Receives one decoded frame and the frame's full, unsubsampled dimensions. */
    void accept(BufferedImage frame, int fullWidth, int fullHeight) throws IOException;
  }

  private ImageFrames() {
  }

  /** Every frame of a multi-frame TIFF, or an empty list for anything else. */
  static List<BufferedImage> tiffFrames(Path image) throws IOException {
    List<BufferedImage> frames = new ArrayList<>();
    try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) {
        return List.of();
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in, false, true);
        if (!isTiff(reader)) {
          return List.of();
        }
        int count = reader.getNumImages(true);
        for (int i = 0; i < count; i++) {
          frames.add(reader.read(i));
        }
      } catch (IIOException e) {
        // Leave the TIFF to PDFBox's single-image path, which also reads CCITT fax encodings.
        return List.of();
      } finally {
        reader.dispose();
      }
    }
    return frames;
  }

  /**
   * Decodes each frame upright, applying a JPEG's EXIF orientation, and subsampling by the factor
   * {@code subsampling} returns for the frame's full, upright width and height.
   */
  static void read(Path image, IntBinaryOperator subsampling, FrameConsumer consumer)
      throws IOException {
    int orientation = ExifOrientation.of(image);
    boolean swap = ExifOrientation.swapsAxes(orientation);
    FrameConsumer upright = (frame, fullWidth, fullHeight) -> consumer.accept(
        ExifOrientation.apply(frame, orientation),
        swap ? fullHeight : fullWidth, swap ? fullWidth : fullHeight);
    IntBinaryOperator uprightSubsampling = (width, height) ->
        swap ? subsampling.applyAsInt(height, width) : subsampling.applyAsInt(width, height);
    readStored(image, uprightSubsampling, upright);
  }

  private static void readStored(Path image, IntBinaryOperator subsampling,
      FrameConsumer consumer) throws IOException {
    int delivered = 0;
    try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
      Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
      if (readers != null && readers.hasNext()) {
        ImageReader reader = readers.next();
        try {
          reader.setInput(in, false, true);
          int count = isTiff(reader) ? reader.getNumImages(true) : 1;
          for (int i = 0; i < count; i++) {
            int width = reader.getWidth(i);
            int height = reader.getHeight(i);
            ImageReadParam param = reader.getDefaultReadParam();
            int factor = Math.max(1, subsampling.applyAsInt(width, height));
            param.setSourceSubsampling(factor, factor, 0, 0);
            consumer.accept(reader.read(i, param), width, height);
            delivered++;
          }
          return;
        } catch (IIOException e) {
          if (delivered > 0) {
            throw e;
          }
        } finally {
          reader.dispose();
        }
      }
    }
    try (PDDocument scratch = new PDDocument()) {
      BufferedImage frame =
          PDImageXObject.createFromFileByContent(image.toFile(), scratch).getImage();
      consumer.accept(frame, frame.getWidth(), frame.getHeight());
    }
  }

  private static boolean isTiff(ImageReader reader) throws IOException {
    String format = reader.getFormatName();
    return "tif".equalsIgnoreCase(format) || "tiff".equalsIgnoreCase(format);
  }
}
