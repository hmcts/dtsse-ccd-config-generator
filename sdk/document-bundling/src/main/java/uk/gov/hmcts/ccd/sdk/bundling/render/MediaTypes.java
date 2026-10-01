package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.bundling.api.BuiltInMediaTypes;

final class MediaTypes {
  static final int DETECTION_PREFIX_BYTES = 1024;

  private static final Set<String> CONTAINER_INCOMPATIBLE = Set.of(
      BuiltInMediaTypes.PDF, "image/png", "image/jpeg", "image/jpg", "image/gif", "image/bmp",
      "image/tiff", "audio/mpeg", "video/mp4");

  private MediaTypes() {
  }

  sealed interface Routing {
    record Route(String mediaType) implements Routing {
    }

    record RouteWithMismatch(String mediaType, String declared, String detected)
        implements Routing {
    }

    record Irreconcilable(String declared, String detectedDescription) implements Routing {
    }
  }

  static String normalise(String mediaType) {
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

  static Routing route(String declared, byte[] head, int length) {
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
      if (canonical(declared).equals(BuiltInMediaTypes.PDF)) {
        return new Routing.Route(declared);
      }
      return new Routing.RouteWithMismatch(
          BuiltInMediaTypes.PDF, declared, BuiltInMediaTypes.PDF);
    }
    return new Routing.Route(declared);
  }

  private static String canonical(String type) {
    return "image/jpg".equals(type) ? "image/jpeg" : type;
  }

  private static Optional<String> detectExact(byte[] head, int length) {
    if (startsWith(head, length, '%', 'P', 'D', 'F', '-')) {
      return Optional.of(BuiltInMediaTypes.PDF);
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
