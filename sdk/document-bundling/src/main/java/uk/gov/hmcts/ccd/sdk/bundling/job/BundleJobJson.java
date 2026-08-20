package uk.gov.hmcts.ccd.sdk.bundling.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.CcdBundle;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentFailure;

/**
 * The outbox wire format: serialises job payloads to the JSON persisted in the
 * {@code ccd_bundle_job} row and reads them back. The mapper is module-owned so the stored shape
 * never varies with a consumer's {@code ObjectMapper} customisations, and
 * {@link #REQUEST_VERSION} stamps every row so a worker that cannot read an old request fails
 * clearly instead of guessing.
 */
final class BundleJobJson {

  /** The version of the request JSON this worker writes and the newest it can read. */
  static final int REQUEST_VERSION = 1;

  private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
  };
  private static final TypeReference<List<DocumentFailure>> DOCUMENT_FAILURES =
      new TypeReference<>() {
      };
  private static final TypeReference<List<BundleJobTransientFailure>> TRANSIENT_HISTORY =
      new TypeReference<>() {
      };

  private final ObjectMapper mapper = JsonMapper.builder()
      .findAndAddModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .build();

  /**
   * Serialises a bundle request for persistence.
   *
   * @param request the submitted request
   * @return the request JSON
   */
  String writeRequest(BundleRequest request) {
    return write(request, "bundle request");
  }

  /**
   * Reads a persisted bundle request.
   *
   * @param json the stored request JSON
   * @return the request
   * @throws BundleJobPayloadException if the stored JSON cannot be read as a request
   */
  BundleRequest readRequest(String json) {
    return read(json, BundleRequest.class, "bundle request");
  }

  /**
   * Serialises an execution context for persistence.
   *
   * @param context the non-secret execution context
   * @return the context JSON
   */
  String writeContext(BundleExecutionContext context) {
    return write(context, "execution context");
  }

  /**
   * Reads a persisted execution context.
   *
   * @param json the stored context JSON
   * @return the context
   * @throws BundleJobPayloadException if the stored JSON cannot be read as a context
   */
  BundleExecutionContext readContext(String json) {
    return read(json, BundleExecutionContext.class, "execution context");
  }

  /**
   * Serialises selector parameters for persistence.
   *
   * @param parameters the consumer's selector parameters
   * @return the parameters JSON
   */
  String writeParameters(Map<String, String> parameters) {
    return write(parameters, "selector parameters");
  }

  /**
   * Reads persisted selector parameters.
   *
   * @param json the stored parameters JSON
   * @return the parameters
   * @throws BundleJobPayloadException if the stored JSON cannot be read as parameters
   */
  Map<String, String> readParameters(String json) {
    try {
      return mapper.readValue(json, STRING_MAP);
    } catch (Exception e) {
      throw new BundleJobPayloadException("the stored selector parameters could not be read", e);
    }
  }

  /**
   * Serialises the published bundle for persistence.
   *
   * @param bundle the CCD-shaped result
   * @return the result JSON
   */
  String writeBundle(CcdBundle bundle) {
    return write(bundle, "bundle result");
  }

  /**
   * Reads a persisted bundle result.
   *
   * @param json the stored result JSON
   * @return the CCD-shaped bundle
   */
  CcdBundle readBundle(String json) {
    try {
      return mapper.readValue(json, CcdBundle.class);
    } catch (Exception e) {
      throw new IllegalStateException("A stored bundle result could not be read", e);
    }
  }

  /**
   * Serialises a failure's document failures for persistence.
   *
   * @param failures the responsible documents
   * @return the document failures JSON
   */
  String writeDocumentFailures(List<DocumentFailure> failures) {
    return write(failures, "document failures");
  }

  /**
   * Reads persisted document failures.
   *
   * @param json the stored document failures JSON
   * @return the document failures
   */
  List<DocumentFailure> readDocumentFailures(String json) {
    try {
      return mapper.readValue(json, DOCUMENT_FAILURES);
    } catch (Exception e) {
      throw new IllegalStateException("Stored document failures could not be read", e);
    }
  }

  /**
   * Serialises a job's transient failure history for persistence.
   *
   * @param history the transient failures recorded so far
   * @return the history JSON
   */
  String writeHistory(List<BundleJobTransientFailure> history) {
    return write(history, "transient history");
  }

  /**
   * Reads a job's persisted transient failure history. The history is diagnostic, so an
   * unreadable value degrades to an empty history rather than failing the job.
   *
   * @param json the stored history JSON
   * @return the history, or an empty list if it cannot be read
   */
  List<BundleJobTransientFailure> readHistory(String json) {
    if (json == null) {
      return List.of();
    }
    try {
      return mapper.readValue(json, TRANSIENT_HISTORY);
    } catch (Exception e) {
      return List.of();
    }
  }

  private String write(Object value, String what) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialise the " + what + " for the outbox", e);
    }
  }

  private <T> T read(String json, Class<T> type, String what) {
    try {
      return mapper.readValue(json, type);
    } catch (Exception e) {
      throw new BundleJobPayloadException("the stored " + what + " could not be read", e);
    }
  }
}
