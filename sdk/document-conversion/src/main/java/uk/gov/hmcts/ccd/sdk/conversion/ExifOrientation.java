package uk.gov.hmcts.ccd.sdk.conversion;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The EXIF orientation of a JPEG: how the stored pixels must be turned or flipped to display
 * upright. Phones store a portrait photo as landscape pixels with orientation 6, and every
 * viewer turns it, so a converter that ignores the tag prints the photo sideways.
 *
 * <p>Values follow the EXIF specification: 1 is upright, 3 is upside down, 6 needs a quarter
 * turn clockwise and 8 a quarter turn counter-clockwise; 2, 4, 5 and 7 are their mirror images.
 */
final class ExifOrientation {
  static final int UPRIGHT = 1;

  private static final int ORIENTATION_TAG = 0x0112;
  private static final int SHORT = 3;
  private static final int LONG = 4;

  private ExifOrientation() {
  }

  /**
   * The orientation of a JPEG, or {@link #UPRIGHT} for anything without a readable tag.
   */
  static int of(Path image) throws IOException {
    try (DataInputStream in = new DataInputStream(Files.newInputStream(image))) {
      if (in.readUnsignedShort() != 0xFFD8) {
        return UPRIGHT;
      }
      while (true) {
        if (in.readUnsignedByte() != 0xFF) {
          return UPRIGHT;
        }
        int code = in.readUnsignedByte();
        // Any number of 0xFF fill bytes may precede a marker code.
        while (code == 0xFF) {
          code = in.readUnsignedByte();
        }
        int marker = 0xFF00 | code;
        // Image data starts at start-of-scan; EXIF can only come before it.
        if (marker == 0xFFDA || marker == 0xFFD9) {
          return UPRIGHT;
        }
        int length = in.readUnsignedShort() - 2;
        if (length < 0) {
          return UPRIGHT;
        }
        if (marker == 0xFFE1) {
          byte[] segment = in.readNBytes(length);
          if (isExif(segment)) {
            return orientation(segment);
          }
        } else {
          in.skipNBytes(length);
        }
      }
    } catch (EOFException e) {
      return UPRIGHT;
    }
  }

  /** Whether the orientation swaps width and height, as the quarter turns do. */
  static boolean swapsAxes(int orientation) {
    return orientation >= 5 && orientation <= 8;
  }

  /** The image as it should be displayed. */
  static BufferedImage apply(BufferedImage image, int orientation) {
    if (orientation < 2 || orientation > 8) {
      return image;
    }
    int w = image.getWidth();
    int h = image.getHeight();
    // Each transform maps stored pixel coordinates to displayed ones:
    // (m00, m10, m01, m11, m02, m12) gives x' = m00 x + m01 y + m02, y' = m10 x + m11 y + m12.
    AffineTransform transform = switch (orientation) {
      case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
      case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
      case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
      case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
      case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
      case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
      default -> new AffineTransform(0, -1, 1, 0, 0, w);
    };
    boolean swap = swapsAxes(orientation);
    BufferedImage turned = new BufferedImage(swap ? h : w, swap ? w : h,
        image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB
            : BufferedImage.TYPE_INT_RGB);
    Graphics2D g = turned.createGraphics();
    try {
      g.drawImage(image, transform, null);
    } finally {
      g.dispose();
    }
    return turned;
  }

  private static boolean isExif(byte[] segment) {
    return segment.length >= 14 && segment[0] == 'E' && segment[1] == 'x' && segment[2] == 'i'
        && segment[3] == 'f' && segment[4] == 0 && segment[5] == 0;
  }

  /** Reads tag 0x0112 from IFD0 of the TIFF structure after the six-byte EXIF header. */
  private static int orientation(byte[] segment) {
    final int tiff = 6;
    boolean littleEndian = segment[tiff] == 'I' && segment[tiff + 1] == 'I';
    if (!littleEndian && !(segment[tiff] == 'M' && segment[tiff + 1] == 'M')) {
      return UPRIGHT;
    }
    long ifd = tiff + unsigned32(segment, tiff + 4, littleEndian);
    if (ifd < tiff + 8 || ifd + 2 > segment.length) {
      return UPRIGHT;
    }
    int entries = unsigned16(segment, (int) ifd, littleEndian);
    for (int i = 0; i < entries; i++) {
      int entry = (int) ifd + 2 + i * 12;
      if (entry + 12 > segment.length) {
        return UPRIGHT;
      }
      if (unsigned16(segment, entry, littleEndian) == ORIENTATION_TAG) {
        int type = unsigned16(segment, entry + 2, littleEndian);
        long value = type == SHORT ? unsigned16(segment, entry + 8, littleEndian)
            : type == LONG ? unsigned32(segment, entry + 8, littleEndian) : 0;
        return value >= 1 && value <= 8 ? (int) value : UPRIGHT;
      }
    }
    return UPRIGHT;
  }

  private static int unsigned16(byte[] b, int at, boolean littleEndian) {
    int first = b[at] & 0xFF;
    int second = b[at + 1] & 0xFF;
    return littleEndian ? first | second << 8 : first << 8 | second;
  }

  private static long unsigned32(byte[] b, int at, boolean littleEndian) {
    long high = unsigned16(b, littleEndian ? at + 2 : at, littleEndian);
    long low = unsigned16(b, littleEndian ? at : at + 2, littleEndian);
    return high << 16 | low;
  }
}
