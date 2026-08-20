package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.bundling.api.BuiltInMediaTypes;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.EmptySectionPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerRegistry;
import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;

/**
 * Step 1 of the pipeline: request validation before any content is read.
 *
 * <p>The tree invariants (unique ids, safe file name, non-empty bundle, non-blank titles) are
 * enforced by the request builder at construction, but they are re-run here deliberately: a
 * durable job deserialises stored requests, and validation must not depend on every historical
 * writer having used the builder. On top of the tree checks, this step enforces what only the
 * renderer knows: the document-count limit, that every media document declares an access URL and
 * a media type renderable from metadata (never a content type such as PDF/image/office, whose
 * handlers read fetched bytes), and that every declared media type has a registered handler.
 */
final class RequestValidation {

  private RequestValidation() {
  }

  static void validate(BundleRequest request, HandlerRegistry registry, BundleLimits limits) {
    List<String> problems = new ArrayList<>();
    validateTitle(request, problems);
    validateFileName(request, problems);
    List<BundleDocument> documents = request.allDocuments();
    validateUniqueIds(documents, problems);
    if (documents.isEmpty() && !hasPlaceholderSection(request.root())) {
      problems.add("the bundle contains no documents and no section whose emptySectionPolicy is "
          + "INCLUDE_PLACEHOLDER");
    }
    List<DocumentFailure> documentFailures = new ArrayList<>();
    validateMediaDocuments(documents, documentFailures);
    if (!problems.isEmpty() || !documentFailures.isEmpty()) {
      throw new BundleGenerationException(
          BundleErrorCode.REQUEST_INVALID,
          BundleStage.VALIDATE,
          problems.isEmpty()
              ? "The bundle request failed validation."
              : "The bundle request failed validation: " + String.join("; ", problems) + ".",
          "Correct the bundle request and resubmit.",
          documentFailures);
    }

    validateMediaTypesRegistered(documents, registry);

    if (documents.size() > limits.maxDocumentCount()) {
      throw new BundleGenerationException(
          BundleErrorCode.LIMIT_EXCEEDED,
          BundleStage.VALIDATE,
          "The bundle contains " + documents.size() + " documents, which exceeds the configured "
              + "maximum of " + limits.maxDocumentCount() + ".",
          "Split the bundle or raise BundleLimits.maxDocumentCount with evidence.",
          List.of());
    }
  }

  private static void validateTitle(BundleRequest request, List<String> problems) {
    if (request.title() == null || request.title().isBlank()) {
      problems.add("the bundle title is blank");
    }
  }

  private static void validateFileName(BundleRequest request, List<String> problems) {
    String fileName = request.fileName();
    if (fileName == null || fileName.isBlank()) {
      problems.add("the output file name is blank");
      return;
    }
    boolean unsafe = fileName.contains("/") || fileName.contains("\\")
        || fileName.contains("..") || fileName.chars().anyMatch(c -> c < 0x20);
    if (unsafe) {
      problems.add("the output file name '" + fileName + "' contains path segments or control "
          + "characters");
    }
    if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
      problems.add("the output file name '" + fileName + "' does not end with .pdf");
    }
  }

  private static void validateUniqueIds(List<BundleDocument> documents, List<String> problems) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (BundleDocument document : documents) {
      counts.merge(document.id(), 1, Integer::sum);
    }
    List<String> duplicates = counts.entrySet().stream()
        .filter(entry -> entry.getValue() > 1)
        .map(Map.Entry::getKey)
        .toList();
    if (!duplicates.isEmpty()) {
      problems.add("document ids are duplicated: " + duplicates);
    }
  }

  private static void validateMediaDocuments(
      List<BundleDocument> documents, List<DocumentFailure> failures) {
    for (BundleDocument document : documents) {
      if (document.media().isEmpty()) {
        continue;
      }
      MediaPlaceholder media = document.media().get();
      if (media.accessUrl() == null || media.accessUrl().isBlank()) {
        failures.add(new DocumentFailure(document.id(), document.reference(),
            BundleErrorCode.REQUEST_INVALID,
            "The media document has no access URL; the generated link page needs one"));
      }
      String type = MediaTypes.normalise(media.mediaType().orElse(null));
      if (type.isEmpty()) {
        failures.add(new DocumentFailure(document.id(), document.reference(),
            BundleErrorCode.REQUEST_INVALID,
            "The media document declares no media type; set MediaPlaceholder.mediaType "
                + "(for example audio/mpeg or video/mp4) so the document can be routed to its "
                + "handler"));
      } else if (isContentType(type)) {
        failures.add(new DocumentFailure(document.id(), document.reference(),
            BundleErrorCode.REQUEST_INVALID,
            "The media document declares '" + type + "', a content media type whose handler "
                + "converts fetched bytes — but media documents are metadata-only and never "
                + "fetched. Declare a media type rendered from metadata (audio/mpeg, video/mp4, "
                + "or a consumer-registered media type), or supply the document without a "
                + "MediaPlaceholder so its content is resolved"));
      }
    }
  }

  /**
   * Whether a media type belongs to the built-in content handlers (PDF passthrough, image
   * conversion, office conversion), all of which read fetched bytes and can never render a
   * metadata-only media placeholder.
   *
   * @param type the normalised media type
   * @return true when the type's built-in handler expects content
   */
  private static boolean isContentType(String type) {
    return BuiltInMediaTypes.PDF.equals(type)
        || BuiltInMediaTypes.IMAGES.contains(type)
        || BuiltInMediaTypes.OFFICE.contains(type);
  }

  private static void validateMediaTypesRegistered(
      List<BundleDocument> documents, HandlerRegistry registry) {
    List<DocumentFailure> failures = new ArrayList<>();
    for (BundleDocument document : documents) {
      if (document.media().isEmpty()) {
        continue;
      }
      String type = MediaTypes.normalise(document.media().get().mediaType().orElse(""));
      if (registry.handlerFor(type).isEmpty()) {
        failures.add(new DocumentFailure(document.id(), document.reference(),
            BundleErrorCode.MEDIA_TYPE_UNSUPPORTED,
            "The declared media type '" + type + "' has no registered handler"));
      }
    }
    if (!failures.isEmpty()) {
      throw new BundleGenerationException(
          BundleErrorCode.MEDIA_TYPE_UNSUPPORTED,
          BundleStage.VALIDATE,
          failures.size() + " media document(s) declare media types with no registered handler. "
              + "Registered types: " + registry.handledMediaTypes() + ".",
          "Register a handler for the media type through a BundlingExtension, or correct the "
              + "declared type.",
          failures);
    }
  }

  private static boolean hasPlaceholderSection(BundleSection section) {
    if (section.emptySectionPolicy() == EmptySectionPolicy.INCLUDE_PLACEHOLDER) {
      return true;
    }
    return section.sections().stream().anyMatch(RequestValidation::hasPlaceholderSection);
  }
}
