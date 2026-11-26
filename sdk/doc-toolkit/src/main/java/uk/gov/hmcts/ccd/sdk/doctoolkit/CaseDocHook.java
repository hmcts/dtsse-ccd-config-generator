package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.ccd.sdk.CaseEventContext;
import uk.gov.hmcts.ccd.sdk.CaseEventHook;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocJobRepository;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocRequestJson;

/**
 * Runs every {@link CaseDocDefinition} for the case after each case event, queueing a render when
 * a request changed and forgetting the job when a case no longer has its document. Registered by
 * the auto-configuration.
 */
public final class CaseDocHook implements CaseEventHook {
  private static final Logger log = LoggerFactory.getLogger(CaseDocHook.class);

  /** The largest stored request: inline content is stored and hashed on every change. */
  static final int MAX_REQUEST_BYTES = 1024 * 1024;

  private final List<CaseDocDefinition> definitions;
  private final DocJobRepository repository;

  public CaseDocHook(List<CaseDocDefinition> definitions, DocJobRepository repository) {
    this.definitions = List.copyOf(definitions);
    this.repository = Validate.requireNonNull(repository, "repository");
    Set<String> keys = new HashSet<>();
    for (CaseDocDefinition definition : this.definitions) {
      String key = Validate.requireNonBlank(definition.key(), "CaseDocDefinition.key");
      if (!keys.add(key)) {
        throw new IllegalStateException("Two CaseDocDefinitions have the key '" + key + "'");
      }
    }
  }

  @Override
  public void afterCaseEvent(CaseEventContext event) {
    for (CaseDocDefinition definition : definitions) {
      if (!definition.caseTypes().contains(event.caseType())) {
        continue;
      }
      Optional<PdfRequest> request = definition.build(event.caseReference());
      if (request.isEmpty()) {
        if (repository.deleteKeyed(event.caseReference(), definition.key())) {
          log.info("Case {} no longer has a {}", event.caseReference(), definition.key());
        }
        continue;
      }
      String json = DocRequestJson.write(request.get());
      int size = json.getBytes(StandardCharsets.UTF_8).length;
      if (size > MAX_REQUEST_BYTES) {
        throw new IllegalStateException("The " + definition.key() + " request for case "
            + event.caseReference() + " is " + size + " bytes, over " + MAX_REQUEST_BYTES
            + ": put large content in CDAM rather than inline");
      }
      if (repository.queueKeyed(UUID.randomUUID(), event.caseReference(), definition.key(),
          event.revision(), json)) {
        log.info("Case {} {} changed at revision {}; render queued", event.caseReference(),
            definition.key(), event.revision());
      }
    }
  }
}
