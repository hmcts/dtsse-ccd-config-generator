package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleErrorCode;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleStage;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments;

/**
 * Steps 2 and 3 of the pipeline: batched resolution of every unique reference, spooling to the
 * job's temporary directory with SHA-256 computation and byte-limit enforcement during the copy,
 * and the fail-fast that turns any unresolved reference into one exception naming every failed
 * document with its typed reason. Media documents never reach this step — they are metadata-only
 * by design.
 */
final class Resolution {

  private static final Logger log = LoggerFactory.getLogger(Resolution.class);

  /**
   * One spooled source: where it landed plus the facts the report needs.
   *
   * @param file the spooled content file
   * @param size the spooled size in bytes
   * @param sha256 the hex-encoded SHA-256 of the content
   * @param declaredMediaType the media type the resolver declared, normalised
   * @param fileName the source file name the resolver reported
   * @param providerChecksum the resolver-reported checksum, when it supplied one
   */
  record Spooled(
      Path file,
      long size,
      String sha256,
      String declaredMediaType,
      String fileName,
      Optional<String> providerChecksum) {
  }

  private Resolution() {
  }

  /**
   * Resolves and spools every unique reference among the fetched (non-media) documents.
   *
   * @param documents the bundle's documents in render order, media documents included (skipped)
   * @param resolvers the registered resolvers by provider
   * @param context the execution context passed through to resolvers
   * @param jobDirectory the job's temporary directory
   * @param limits the effective limits
   * @param deadline the render's deadline checkpoint, invoked before each reference and between
   *     copy chunks while spooling; it throws the typed timeout when the budget is spent
   * @return the spooled content by reference, one entry per unique reference
   * @throws BundleGenerationException if any reference could not be resolved and spooled,
   *     carrying a failure for every affected document, or when the deadline elapses
   */
  static Map<DocumentReference, Spooled> resolveAndSpool(
      List<BundleDocument> documents,
      Map<String, DocumentResolver> resolvers,
      BundleExecutionContext context,
      Path jobDirectory,
      BundleLimits limits,
      Runnable deadline) {
    List<BundleDocument> fetched = documents.stream()
        .filter(document -> document.media().isEmpty())
        .toList();
    Set<DocumentReference> unique = new LinkedHashSet<>();
    fetched.forEach(document -> unique.add(document.reference()));

    failOnUnknownProviders(fetched, resolvers);

    Map<DocumentReference, Spooled> spooled = new LinkedHashMap<>();
    Map<DocumentReference, ResolutionOutcome> failures = new LinkedHashMap<>();
    Map<String, List<DocumentReference>> byProvider = new LinkedHashMap<>();
    for (DocumentReference reference : unique) {
      byProvider.computeIfAbsent(reference.provider(), provider -> new ArrayList<>())
          .add(reference);
    }

    for (Map.Entry<String, List<DocumentReference>> batch : byProvider.entrySet()) {
      DocumentResolver resolver = resolvers.get(batch.getKey());
      resolveBatch(resolver, batch.getValue(), context, jobDirectory, limits, deadline,
          spooled, failures);
    }

    if (!failures.isEmpty()) {
      throw aggregateFailure(fetched, failures, unique.size());
    }
    return spooled;
  }

  private record ResolutionOutcome(BundleErrorCode code, String detail) {
  }

  private static void failOnUnknownProviders(
      List<BundleDocument> fetched, Map<String, DocumentResolver> resolvers) {
    List<DocumentFailure> unknown = fetched.stream()
        .filter(document -> !resolvers.containsKey(document.reference().provider()))
        .map(document -> new DocumentFailure(document.id(), document.reference(),
            BundleErrorCode.DOCUMENT_RESOLUTION_FAILED,
            "No DocumentResolver is registered for provider '"
                + document.reference().provider() + "'"))
        .toList();
    if (!unknown.isEmpty()) {
      Set<String> providers = new LinkedHashSet<>();
      unknown.forEach(failure -> providers.add(failure.reference().provider()));
      throw new BundleGenerationException(
          BundleErrorCode.DOCUMENT_RESOLUTION_FAILED,
          BundleStage.RESOLVE,
          unknown.size() + " document(s) reference unregistered resolver provider(s) "
              + providers + "; registered providers: " + resolvers.keySet() + ".",
          "Register a DocumentResolver for each provider on the renderer builder.",
          unknown);
    }
  }

  private static void resolveBatch(
      DocumentResolver resolver,
      List<DocumentReference> references,
      BundleExecutionContext context,
      Path jobDirectory,
      BundleLimits limits,
      Runnable deadline,
      Map<DocumentReference, Spooled> spooled,
      Map<DocumentReference, ResolutionOutcome> failures) {
    ResolvedDocuments outcomes;
    try {
      outcomes = resolver.resolveAll(List.copyOf(references), context);
    } catch (RuntimeException e) {
      log.warn("Resolver for provider '{}' threw {} for its whole batch",
          resolver.provider(), e.getClass().getSimpleName());
      for (DocumentReference reference : references) {
        failures.put(reference, new ResolutionOutcome(
            BundleErrorCode.DOCUMENT_RESOLUTION_FAILED,
            "The resolver threw " + e.getClass().getSimpleName() + " for the whole batch"));
      }
      return;
    }
    try {
      for (DocumentReference reference : references) {
        deadline.run();
        ResolvedDocument resolved = outcomes.resolved().get(reference);
        ResolutionFailure failure = outcomes.failures().get(reference);
        if (resolved != null) {
          spoolOne(reference, resolved, jobDirectory, limits, deadline, spooled, failures);
        } else if (failure != null) {
          failures.put(reference,
              new ResolutionOutcome(map(failure.reason()), failure.detail()));
        } else {
          failures.put(reference, new ResolutionOutcome(
              BundleErrorCode.DOCUMENT_RESOLUTION_FAILED,
              "The resolver returned no outcome for the reference"));
        }
      }
    } finally {
      closeAll(outcomes);
    }
  }

  private static void spoolOne(
      DocumentReference reference,
      ResolvedDocument resolved,
      Path jobDirectory,
      BundleLimits limits,
      Runnable deadline,
      Map<DocumentReference, Spooled> spooled,
      Map<DocumentReference, ResolutionOutcome> failures) {
    long limit = limits.maxSourceBytesPerDocument();
    if (resolved.contentLength().isPresent() && resolved.contentLength().getAsLong() > limit) {
      failures.put(reference, new ResolutionOutcome(BundleErrorCode.LIMIT_EXCEEDED,
          "The source declares " + resolved.contentLength().getAsLong() + " bytes, which "
              + "exceeds the configured maximum of " + limit + " bytes per document"));
      return;
    }
    Path file = null;
    try {
      file = JobDirectory.createFile(jobDirectory, ".src");
      MessageDigest digest = sha256Digest();
      long copied = 0;
      try (InputStream in = resolved.content();
          OutputStream out = Files.newOutputStream(file)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
          deadline.run();
          copied += read;
          if (copied > limit) {
            failures.put(reference, new ResolutionOutcome(BundleErrorCode.LIMIT_EXCEEDED,
                "The source exceeded the configured maximum of " + limit + " bytes per "
                    + "document during transfer (it declared "
                    + (resolved.contentLength().isPresent()
                        ? resolved.contentLength().getAsLong() + " bytes" : "no length") + ")"));
            return;
          }
          digest.update(buffer, 0, read);
          out.write(buffer, 0, read);
        }
      }
      spooled.put(reference, new Spooled(
          file,
          copied,
          HexFormat.of().formatHex(digest.digest()),
          MediaTypes.normalise(resolved.mediaType()),
          resolved.fileName(),
          resolved.checksum()));
      file = null;
    } catch (BundleGenerationException e) {
      // The deadline checkpoint fired mid-copy: the timeout is a whole-render failure, never a
      // per-document outcome. The partial spool file is removed by the finally below.
      throw e;
    } catch (IOException | RuntimeException e) {
      failures.put(reference, new ResolutionOutcome(
          BundleErrorCode.DOCUMENT_RESOLUTION_FAILED,
          "The source content could not be spooled to disk: " + e.getClass().getSimpleName()));
    } finally {
      if (file != null) {
        deleteQuietly(file);
      }
    }
  }

  private static BundleGenerationException aggregateFailure(
      List<BundleDocument> fetched,
      Map<DocumentReference, ResolutionOutcome> failures,
      int uniqueCount) {
    List<DocumentFailure> documentFailures = new ArrayList<>();
    for (BundleDocument document : fetched) {
      ResolutionOutcome outcome = failures.get(document.reference());
      if (outcome != null) {
        documentFailures.add(new DocumentFailure(
            document.id(), document.reference(), outcome.code(), outcome.detail()));
      }
    }
    Set<BundleErrorCode> codes = new LinkedHashSet<>();
    documentFailures.forEach(failure -> codes.add(failure.code()));
    BundleErrorCode code = codes.size() == 1
        ? codes.iterator().next()
        : BundleErrorCode.DOCUMENT_RESOLUTION_FAILED;
    return new BundleGenerationException(
        code,
        BundleStage.RESOLVE,
        failures.size() + " of " + uniqueCount + " unique document reference(s) could not be "
            + "resolved; every document in the request must stitch, so nothing was published.",
        "Check the failed documents still exist and are accessible to the execution context, "
            + "then resubmit the bundle.",
        documentFailures);
  }

  private static BundleErrorCode map(
      uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason reason) {
    return switch (reason) {
      case NOT_FOUND -> BundleErrorCode.DOCUMENT_NOT_FOUND;
      case ACCESS_DENIED -> BundleErrorCode.DOCUMENT_ACCESS_DENIED;
      case UNSUPPORTED_MEDIA_TYPE -> BundleErrorCode.MEDIA_TYPE_UNSUPPORTED;
      case INVALID_CONTENT -> BundleErrorCode.DOCUMENT_CONTENT_INVALID;
      case TOO_LARGE -> BundleErrorCode.LIMIT_EXCEEDED;
      case TRANSIENT_FAILURE -> BundleErrorCode.DOCUMENT_RESOLUTION_FAILED;
    };
  }

  private static void closeAll(ResolvedDocuments outcomes) {
    for (ResolvedDocument resolved : outcomes.resolved().values()) {
      try {
        resolved.close();
      } catch (Exception e) {
        log.warn("Closing a resolved document failed: {}", e.getClass().getSimpleName());
      }
    }
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a mandatory JVM algorithm", e);
    }
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Could not delete a partially spooled file: {}", e.toString());
    }
  }
}
