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
 * CMYK JPEGs. Only TIFFs yield more than one page: a GIF's later frames are animation, not
 * pages, and a TIFF's reduced-resolution images and masks are not pages either.
 */
final class ImageFrames {

  @FunctionalInterface
  interface FrameConsumer {
    /** Receives one decoded frame and the frame's full, unsubsampled dimensions. */
    void accept(BufferedImage frame, int fullWidth, int fullHeight) throws IOException;
  }

  private ImageFrames() {
  }

  /**
   * Decoded images above this many pixels are refused on the PDFBox fallback path, which cannot
   * subsample: about 200 MB as an RGB raster, and several copies are made before downsampling.
   */
  static final long MAX_FALLBACK_PIXELS = 50_000_000L;

  @FunctionalInterface
  interface PageConsumer {
    /** Receives one page of a multi-page TIFF at full resolution. */
    void accept(BufferedImage page) throws IOException;
  }

  /**
   * Decodes the pages of a multi-page TIFF one at a time, skipping thumbnails and masks; returns
   * how many there were. Nothing is decoded, and 0 or 1 is returned, for anything that is not a
   * TIFF with more than one page, which callers handle as a single image.
   */
  static int tiffPages(Path image, PageConsumer consumer) throws IOException {
    try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
      Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
      if (readers == null || !readers.hasNext()) {
        return 0;
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in, false, false);
        if (!isTiff(reader)) {
          return 0;
        }
        List<Integer> pages = new ArrayList<>();
        int count = reader.getNumImages(true);
        for (int i = 0; i < count; i++) {
          if (!TiffTags.of(reader, i).isReducedOrMask()) {
            pages.add(i);
          }
        }
        if (pages.size() > 1) {
          for (int page : pages) {
            consumer.accept(reader.read(page));
          }
        }
        return pages.size();
      } catch (IIOException e) {
        // Leave the TIFF to PDFBox's single-image path, which also reads CCITT fax encodings.
        return 0;
      } finally {
        reader.dispose();
      }
    }
  }

  /**
   * Decodes each page upright, applying a JPEG's EXIF orientation or a TIFF page's orientation
   * tag, and subsampling by the factor {@code subsampling} returns for the page's full, upright
   * width and height. A TIFF's thumbnails and masks are skipped.
   */
  static void read(Path image, IntBinaryOperator subsampling, FrameConsumer consumer)
      throws IOException {
    final int jpegOrientation = ExifOrientation.of(image);
    int delivered = 0;
    long fallbackPixels = 0;
    try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
      Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
      if (readers != null && readers.hasNext()) {
        ImageReader reader = readers.next();
        try {
          reader.setInput(in, false, false);
          boolean tiff = isTiff(reader);
          int count = tiff ? reader.getNumImages(true) : 1;
          for (int i = 0; i < count; i++) {
            int width = reader.getWidth(i);
            int height = reader.getHeight(i);
            fallbackPixels = (long) width * height;
            int orientation = jpegOrientation;
            if (tiff) {
              TiffTags tags = TiffTags.of(reader, i);
              if (tags.isReducedOrMask()) {
                continue;
              }
              orientation = tags.orientation();
            }
            boolean swap = ExifOrientation.swapsAxes(orientation);
            int uprightWidth = swap ? height : width;
            int uprightHeight = swap ? width : height;
            ImageReadParam param = reader.getDefaultReadParam();
            int factor = Math.max(1, subsampling.applyAsInt(uprightWidth, uprightHeight));
            param.setSourceSubsampling(factor, factor, 0, 0);
            consumer.accept(ExifOrientation.apply(reader.read(i, param), orientation),
                uprightWidth, uprightHeight);
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
    if (fallbackPixels > MAX_FALLBACK_PIXELS) {
      throw new IOException("The image is " + fallbackPixels + " pixels in a form only decodable "
          + "at full size, above the " + MAX_FALLBACK_PIXELS + " pixel limit");
    }
    try (PDDocument scratch = new PDDocument()) {
      BufferedImage frame = ExifOrientation.apply(
          PDImageXObject.createFromFileByContent(image.toFile(), scratch).getImage(),
          jpegOrientation);
      consumer.accept(frame, frame.getWidth(), frame.getHeight());
    }
  }

  private static boolean isTiff(ImageReader reader) throws IOException {
    String format = reader.getFormatName();
    return "tif".equalsIgnoreCase(format) || "tiff".equalsIgnoreCase(format);
  }
}
