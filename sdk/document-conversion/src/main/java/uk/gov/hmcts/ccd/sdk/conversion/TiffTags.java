package uk.gov.hmcts.ccd.sdk.conversion;

import java.io.IOException;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import org.w3c.dom.Node;

/**
 * The tags of one TIFF image file directory that decide how it prints: NewSubfileType (254),
 * which marks thumbnails and transparency masks, and Orientation (274), with the same values as
 * EXIF orientation.
 */
record TiffTags(long newSubfileType, int orientation) {
  private static final String FORMAT = "javax_imageio_tiff_image_1.0";
  private static final int NEW_SUBFILE_TYPE = 254;
  private static final int ORIENTATION = 274;

  /**
   * The tags of image {@code index}, or the defaults when the reader exposes none.
   */
  static TiffTags of(ImageReader reader, int index) throws IOException {
    IIOMetadata metadata = reader.getImageMetadata(index);
    if (metadata == null || !FORMAT.equals(metadata.getNativeMetadataFormatName())) {
      return new TiffTags(0, ExifOrientation.UPRIGHT);
    }
    long subfileType = 0;
    int orientation = ExifOrientation.UPRIGHT;
    for (Node ifd = metadata.getAsTree(FORMAT).getFirstChild(); ifd != null;
        ifd = ifd.getNextSibling()) {
      for (Node field = ifd.getFirstChild(); field != null; field = field.getNextSibling()) {
        Node number = field.getAttributes() == null ? null
            : field.getAttributes().getNamedItem("number");
        if (number == null) {
          continue;
        }
        int tag = Integer.parseInt(number.getNodeValue());
        if (tag == NEW_SUBFILE_TYPE) {
          subfileType = firstValue(field, 0);
        } else if (tag == ORIENTATION) {
          long value = firstValue(field, ExifOrientation.UPRIGHT);
          orientation = value >= 1 && value <= 8 ? (int) value : ExifOrientation.UPRIGHT;
        }
      }
    }
    return new TiffTags(subfileType, orientation);
  }

  /** A reduced-resolution copy of another page (bit 0) or a transparency mask (bit 2). */
  boolean isReducedOrMask() {
    return (newSubfileType & 0b101) != 0;
  }

  /** The value of a field's first TIFFShort or TIFFLong, ignoring any other kinds. */
  private static long firstValue(Node field, long fallback) {
    for (Node values = field.getFirstChild(); values != null; values = values.getNextSibling()) {
      Node value = values.getFirstChild();
      if (value != null && value.getAttributes() != null
          && value.getAttributes().getNamedItem("value") != null) {
        try {
          return Long.parseLong(value.getAttributes().getNamedItem("value").getNodeValue());
        } catch (NumberFormatException e) {
          return fallback;
        }
      }
    }
    return fallback;
  }
}
