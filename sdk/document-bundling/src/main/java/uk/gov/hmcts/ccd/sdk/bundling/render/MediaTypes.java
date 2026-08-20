package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.bundling.api.BuiltInMediaTypes;

/**
 * Media-type normalisation and content-based detection.
 *
 * <p>The detection policy, in force at the start of the CONVERT stage for every fetched
 * document:
 *
 * <ul>
 * <li>An exact anchored (offset-0) signature match (PDF, PNG, JPEG, GIF, BMP, TIFF, RTF, MP3,
 * MP4) is confident, so the detected type wins for handler routing; when it disagrees with the
 * declared type the pipeline records a warning rather than trusting the declaration — document
 * stores carry uploader-supplied types verbatim and lie routinely. The windowed {@code %PDF-}
 * scan of the first kilobyte (the PDF specification permits leading junk) runs only when no
 * anchored or container signature matched, so a ZIP entry name or PNG comment containing those
 * five bytes can never turn a valid non-PDF into a "PDF".
 * <li>A container signature (ZIP or OLE2) is not a confident type on its own — it underlies
 * every Office format and plenty of others — so the declared type wins when it is plausibly
 * container-backed (any Office type, or a type this detector does not know). A declared PDF,
 * image, or audio/video type over a container signature is irreconcilable and fails the bundle
 * with {@code DOCUMENT_CONTENT_INVALID}, naming both types.
 * <li>No signature at all (plain text has none) leaves the declared type in charge; the routed
 * handler's own validation is the backstop for a lie that gets this far.
 * <li>A confident detection of an audio/video type on a document that was fetched as content is
 * irreconcilable too: media documents are metadata-only by design, so recorded media resolved as
 * bytes means the request mislabelled the document.
 * </ul>
 */
final class MediaTypes {

  /** How many leading bytes detection needs; the PDF signature may follow up to 1KB of junk. */
  static final int DETECTION_PREFIX_BYTES = 1024;

  private static final Set<String> CONTAINER_INCOMPATIBLE = Set.of(
      BuiltInMediaTypes.PDF, "image/png", "image/jpeg", "image/jpg", "image/gif", "image/bmp",
      "image/tiff", "audio/mpeg", "video/mp4");

  private MediaTypes() {
  }

  /** The outcome of routing one document's declared type against its detected content. */
  sealed interface Routing {

    /** The declared and detected types agree, or the detected type quietly wins. */
    record Route(String mediaType) implements Routing {
    }

    /** The detected type wins over a disagreeing declaration; worth a warning. */
    record RouteWithMismatch(String mediaType, String declared, String detected)
        implements Routing {
    }

    /** The declared and detected types cannot describe the same document. */
    record Irreconcilable(String declared, String detectedDescription) implements Routing {
    }
  }

  /**
   * Normalises a media type for registry lookup: parameters stripped (a resolver may declare
   * {@code application/pdf;charset=UTF-8}), trimmed, lower-cased.
   *
   * @param mediaType the raw media type
   * @return the normalised type, or an empty string for null/blank input
   */
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

  /**
   * Applies the detection policy documented on this class.
   *
   * @param declared the declared media type, already normalised
   * @param head the first bytes of the spooled content
   * @param length how many bytes of {@code head} are valid
   * @return the routing decision
   */
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
