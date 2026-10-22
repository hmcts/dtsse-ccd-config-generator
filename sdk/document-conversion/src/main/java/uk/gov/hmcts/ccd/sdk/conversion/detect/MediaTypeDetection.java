package uk.gov.hmcts.ccd.sdk.conversion.detect;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Content-based media type detection shared by document conversion and bundling.
 *
 * <p>Declared media types come from document-store metadata and are often wrong. {@link #route}
 * checks a declared type against the first {@link #DETECTION_PREFIX_BYTES} bytes of content and
 * decides what the content should be converted as. {@link #fromFileName} infers a type from a
 * file extension for content uploaded without a usable declared type.
 */
public final class MediaTypeDetection {
  /**
   * How many leading bytes {@link #route} needs to see.
   */
  public static final int DETECTION_PREFIX_BYTES = 1024;

  private static final String PDF = "application/pdf";

  private static final Map<String, String> EXTENSIONS = Map.ofEntries(
      Map.entry("pdf", PDF),
      Map.entry("jpg", "image/jpeg"),
      Map.entry("jpeg", "image/jpeg"),
      Map.entry("png", "image/png"),
      Map.entry("bmp", "image/bmp"),
      Map.entry("gif", "image/gif"),
      Map.entry("tif", "image/tiff"),
      Map.entry("tiff", "image/tiff"),
      Map.entry("doc", "application/msword"),
      Map.entry("dot", "application/msword"),
      Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
      Map.entry("dotx", "application/vnd.openxmlformats-officedocument.wordprocessingml.template"),
      Map.entry("xls", "application/vnd.ms-excel"),
      Map.entry("xlt", "application/vnd.ms-excel"),
      Map.entry("xla", "application/vnd.ms-excel"),
      Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
      Map.entry("xltx", "application/vnd.openxmlformats-officedocument.spreadsheetml.template"),
      Map.entry("xlsb", "application/vnd.ms-excel.sheet.binary.macroenabled.12"),
      Map.entry("ppt", "application/vnd.ms-powerpoint"),
      Map.entry("pot", "application/vnd.ms-powerpoint"),
      Map.entry("pps", "application/vnd.ms-powerpoint"),
      Map.entry("ppa", "application/vnd.ms-powerpoint"),
      Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
      Map.entry("potx", "application/vnd.openxmlformats-officedocument.presentationml.template"),
      Map.entry("ppsx", "application/vnd.openxmlformats-officedocument.presentationml.slideshow"),
      Map.entry("txt", "text/plain"),
      Map.entry("rtf", "application/rtf"),
      Map.entry("csv", "text/csv"),
      Map.entry("mp3", "audio/mpeg"),
      Map.entry("mp4", "video/mp4"));

  private static final Set<String> CONTAINER_INCOMPATIBLE = Set.of(
      PDF, "image/png", "image/jpeg", "image/jpg", "image/gif", "image/bmp",
      "image/tiff", "audio/mpeg", "video/mp4");

  private MediaTypeDetection() {
  }

  /** The outcome of checking a declared media type against content. */
  public sealed interface Routing {
    /** The content agrees with the declared type, or nothing contradicts it. */
    record Route(String mediaType) implements Routing {
    }

    /** The content is recognisably another type; it should be converted as the detected one. */
    record RouteWithMismatch(String mediaType, String declared, String detected)
        implements Routing {
    }

    /** The content contradicts the declared type and cannot be any type that would explain it. */
    record Irreconcilable(String declared, String detectedDescription) implements Routing {
    }
  }

  /** Lower-cases a media type and strips its parameters; null becomes the empty string. */
  public static String normalise(String mediaType) {
    if (mediaType == null) {
      return "";
    }
    String type = mediaType;
    int parameters = type.indexOf(';');
    if (parameters >= 0) {
      type = type.substring(0, parameters);
    }
    return type.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * The media type a file name's extension implies, if it is one the SDK knows. Used only when
   * content arrives with no usable declared type.
   */
  public static Optional<String> fromFileName(String fileName) {
    if (fileName == null) {
      return Optional.empty();
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        EXTENSIONS.get(fileName.substring(dot + 1).trim().toLowerCase(Locale.ROOT)));
  }

  /**
   * Checks a declared, {@linkplain #normalise normalised} media type against the first
   * {@code length} bytes of content.
   */
  public static Routing route(String declared, byte[] head, int length) {
    // Anchored offset-0 signatures decide first: a ZIP/OLE2/image whose body happens to
    // contain the bytes %PDF- inside the first kilobyte (a zip entry name, a PNG tEXt
    // comment) must never be mistaken for a PDF.
    Optional<String> exact = detectExact(head, length);
    if (exact.isPresent()) {
      String detected = exact.get();
      if (canonical(declared).equals(canonical(detected))) {
        return new Routing.Route(declared);
      }
      return new Routing.RouteWithMismatch(detected, declared, detected);
    }
    if (isZip(head, length) || isOle2(head, length)) {
      String container = isZip(head, length) ? "a ZIP container" : "an OLE2 container";
      if (CONTAINER_INCOMPATIBLE.contains(declared)) {
        return new Routing.Irreconcilable(declared, container + " that is not '" + declared + "'");
      }
      return new Routing.Route(declared);
    }
    // Only content with no anchored signature at all is windowed-scanned for %PDF-: the PDF
    // specification permits leading junk (scanner preambles), so the signature may sit past
    // offset 0 — but only when nothing else claimed the content first.
    if (containsPdfSignature(head, length)) {
      if (canonical(declared).equals(PDF)) {
        return new Routing.Route(declared);
      }
      return new Routing.RouteWithMismatch(
          PDF, declared, PDF);
    }
    return new Routing.Route(declared);
  }

  private static String canonical(String type) {
    return "image/jpg".equals(type) ? "image/jpeg" : type;
  }

  private static Optional<String> detectExact(byte[] head, int length) {
    if (startsWith(head, length, '%', 'P', 'D', 'F', '-')) {
      return Optional.of(PDF);
    }
    if (startsWith(head, length, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
      return Optional.of("image/png");
    }
    if (startsWith(head, length, 0xFF, 0xD8, 0xFF)) {
      return Optional.of("image/jpeg");
    }
    if (startsWith(head, length, 'G', 'I', 'F', '8')) {
      return Optional.of("image/gif");
    }
    if (startsWith(head, length, 'B', 'M')) {
      return Optional.of("image/bmp");
    }
    if (startsWith(head, length, 'I', 'I', '*', 0) || startsWith(head, length, 'M', 'M', 0, '*')) {
      return Optional.of("image/tiff");
    }
    if (startsWith(head, length, '{', '\\', 'r', 't', 'f')) {
      return Optional.of("application/rtf");
    }
    if (startsWith(head, length, 'I', 'D', '3')
        || startsWith(head, length, 0xFF, 0xFB) || startsWith(head, length, 0xFF, 0xF3)
        || startsWith(head, length, 0xFF, 0xF2)) {
      return Optional.of("audio/mpeg");
    }
    if (length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
      return Optional.of("video/mp4");
    }
    return Optional.empty();
  }

  private static boolean isZip(byte[] head, int length) {
    return startsWith(head, length, 'P', 'K', 3, 4);
  }

  private static boolean isOle2(byte[] head, int length) {
    return startsWith(head, length, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
  }

  private static boolean containsPdfSignature(byte[] head, int length) {
    byte[] signature = {'%', 'P', 'D', 'F', '-'};
    for (int i = 0; i + signature.length <= length; i++) {
      int j = 0;
      while (j < signature.length && head[i + j] == signature[j]) {
        j++;
      }
      if (j == signature.length) {
        return true;
      }
    }
    return false;
  }

  private static boolean startsWith(byte[] head, int length, int... expected) {
    if (length < expected.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if ((head[i] & 0xFF) != (expected[i] & 0xFF)) {
        return false;
      }
    }
    return true;
  }
}
