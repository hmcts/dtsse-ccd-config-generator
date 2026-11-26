package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocJobRepository;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocRequestJson;

/**
 * Renders a case's document in the background and hands each version to the service's
 * {@link DocJobHandler}. Spring Boot services get one when {@code ccd.doc-toolkit.jobs.enabled}
 * is true.
 *
 * <pre>{@code
 * @Component
 * class CaseBundleHook implements CaseEventHook {
 *   public void afterCaseEvent(CaseEventContext event) {
 *     docJobs.submit(event.caseReference(), "case-bundle", bundle.build(event.caseReference()));
 *   }
 * }
 * }</pre>
 */
public final class DocJobs {
  private static final Logger log = LoggerFactory.getLogger(DocJobs.class);

  /** The largest stored request: inline content is stored and hashed on every submit. */
  static final int MAX_REQUEST_BYTES = 1024 * 1024;

  private final DocJobRepository repository;

  /** Created by the auto-configuration. */
  public DocJobs(DocJobRepository repository) {
    this.repository = Validate.requireNonNull(repository, "repository");
  }

  /**
   * Renders {@code request} as the case's document {@code key}. A case has one job per key: a
   * newer request replaces one not yet handed over, and a render of a replaced request is handed
   * to nobody. A request the same as the last one submitted does nothing, so it is cheap to
   * submit after every case event, unless that one failed: then it is tried again.
   *
   * <p>Submit from inside the case event's transaction (a {@code CaseEventHook}), building the
   * request from the case there: the case lock then puts submissions in the order the case
   * changed, and nothing is queued if the event rolls back.
   *
   * @throws IllegalArgumentException if the request holds a local file, which a worker elsewhere
   *     cannot read; use {@link DocSource#bytes} or CDAM
   * @throws IllegalStateException if the request is over 1 MiB; put large content in CDAM
   */
  public void submit(long caseReference, String key, PdfRequest request) {
    Validate.requireNonBlank(key, "key");
    String json = DocRequestJson.write(Validate.requireNonNull(request, "request"));
    int size = json.getBytes(StandardCharsets.UTF_8).length;
    if (size > MAX_REQUEST_BYTES) {
      throw new IllegalStateException("The " + key + " request for case " + caseReference
          + " is " + size + " bytes, over " + MAX_REQUEST_BYTES
          + ": put large content in CDAM rather than inline");
    }
    if (repository.queue(UUID.randomUUID(), caseReference, key, json)) {
      log.info("Case {} {} changed; render queued", caseReference, key);
    }
  }
}
