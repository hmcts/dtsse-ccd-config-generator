package uk.gov.hmcts.ccd.sdk.bundling.job;

import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResult;
import uk.gov.hmcts.ccd.sdk.bundling.api.MissingDocument;
import uk.gov.hmcts.ccd.sdk.bundling.managed.SelectedCaseBundle;

/**
 * JDBC access to bundling.bundle_job, the queue of bundles to render, and to bundling.bundle, which
 * describes the bundles rendered from it. The queue works as the search index queue does: a bundle
 * has a row while a render of it is wanted, a change inserts the row or raises its revision, and a
 * worker that finishes deletes the row only if the revision is the one it claimed. A change during
 * a render therefore leaves the row to be rendered again, and a case has one render of a bundle at
 * a time. Claiming uses FOR UPDATE SKIP LOCKED plus a lease, and every worker-side write is
 * guarded by the lease owner, so a worker that lost its lease gets false back and the lease
 * holder's state stands.
 */
public class BundleJobRepository {
  // Asks for another render of a bundle that has a row. Its lease, if a worker is rendering it, is
  // left to that worker.
  private static final String RENDER_AGAIN = """
      revision = bundle_job.revision + 1, attempts = 0, next_attempt_at = null,
      failure_message = null
      """;
  // The row a worker's claim holds: none if its lease was taken over.
  private static final String CLAIMED = """
      where definition = :definition and case_reference = :caseReference
        and lease_owner = :leaseOwner
      """;
  private static final TypeReference<List<DocumentResult>> DOCUMENT_RESULTS =
      new TypeReference<>() {
      };
  private static final TypeReference<List<MissingDocument>> MISSING_DOCUMENTS =
      new TypeReference<>() {
      };
  private static final TypeReference<List<BundleWarning>> WARNINGS = new TypeReference<>() {
  };

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final BundleJobJson json = new BundleJobJson();

  public BundleJobRepository(NamedParameterJdbcTemplate jdbc,
      PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactions);
  }

  /**
   * Locks the case as an event does, and says whether it exists. Every write here takes its case
   * before its own rows, so it waits for an event, a change, a refresh or a deletion of the case
   * instead of crossing it.
   */
  private boolean lockCase(long caseReference) {
    return jdbc.query("select 1 from ccd.case_data where reference = :caseReference for update",
        params("caseReference", caseReference), ResultSet::next);
  }

  /**
   * Queues the bundle for a change to its documents. The caller holds the row until it commits and
   * the claim passes over a held row, so a bundle is never claimed before the change that queued
   * it is visible. A change makes a waiting retry immediate and a waiting refresh live.
   *
   * <p>The event that creates a case has no case to lock yet.
   */
  void changed(String definition, long caseReference) {
    lockCase(caseReference);
    jdbc.update("""
        insert into bundling.bundle_job (definition, case_reference)
        values (:definition, :caseReference)
        on conflict (case_reference, definition) do update set priority = 0,
        """ + RENDER_AGAIN, params("definition", definition, "caseReference", caseReference));
  }

  /** The case as CCD holds it: where a bundle of the case is stored. */
  SelectedCaseBundle selectedFor(long caseReference, BundleRequest request) {
    return jdbc.queryForObject("""
        select case_type_id, jurisdiction from ccd.case_data where reference = :caseReference
        """, params("caseReference", caseReference),
        (rs, n) -> new SelectedCaseBundle(caseReference, rs.getString("case_type_id"),
            rs.getString("jurisdiction"), request));
  }

  /** Records the refresh key, and says whether it was new. A concurrent caller waits its turn. */
  boolean recordRefresh(String key) {
    return jdbc.update("""
        insert into bundling.refresh_key (key) values (:key) on conflict do nothing
        """, params("key", key)) == 1;
  }

  /**
   * Queues a background render of the definition's bundle for every case of the case type, and
   * returns how many. A bundle already queued keeps its priority. It locks each case before
   * queuing its bundle, so events on a case it has reached wait until it commits.
   */
  int refresh(String definition, String caseType) {
    return jdbc.update("""
        insert into bundling.bundle_job (definition, case_reference, priority)
        select :definition, reference, -1 from ccd.case_data
        where case_type_id = :caseType order by reference for key share
        on conflict (case_reference, definition) do update set
        """ + RENDER_AGAIN, params("definition", definition, "caseType", caseType));
  }

  /** The bundle's queue row: present while a render of it is wanted, or after one failed. */
  public Optional<BundleJob> find(String definition, long caseReference) {
    return jdbc.query("""
        select definition, case_reference, revision, attempts, failure_message
        from bundling.bundle_job
        where definition = :definition and case_reference = :caseReference
        """, params("definition", definition, "caseReference", caseReference),
        (rs, n) -> new BundleJob(rs.getString("definition"), rs.getLong("case_reference"),
            rs.getLong("revision"), rs.getInt("attempts"),
            Optional.ofNullable(rs.getString("failure_message")))).stream().findFirst();
  }

  /**
   * Claims the next bundles: those due, and those whose worker's lease expired. A bundle changed
   * while a worker renders it waits for that worker's lease. A worker claims, and gives up on,
   * only its own definitions, so a pod on an older release leaves a definition it does not have to
   * the pods that do.
   */
  List<ClaimedBundleJob> claim(int limit, String leaseOwner, Duration leaseDuration,
      int maxAttempts, Collection<String> definitions) {
    if (definitions.isEmpty()) {
      return List.of();
    }
    return jdbc.query("""
        with claimable as (
          select definition, case_reference from bundling.bundle_job
          where failure_message is null
            and attempts < :maxAttempts
            and definition in (:definitions)
            and (lease_expires_at is null or lease_expires_at <= now())
            and (next_attempt_at is null or next_attempt_at <= now())
          order by priority desc, queued_at
          limit :limit
          for update skip locked
        )
        update bundling.bundle_job job
        set attempts = job.attempts + 1, lease_owner = :leaseOwner,
            lease_expires_at = now() + (:leaseMillis * interval '1 millisecond'),
            next_attempt_at = null
        from claimable
        where job.definition = claimable.definition
          and job.case_reference = claimable.case_reference
        returning job.definition, job.case_reference, job.revision, job.attempts
        """,
        params("limit", limit, "leaseOwner", leaseOwner, "leaseMillis", leaseDuration.toMillis(),
            "maxAttempts", maxAttempts, "definitions", definitions),
        (rs, n) -> new ClaimedBundleJob(
            new BundleJob(rs.getString("definition"), rs.getLong("case_reference"),
                rs.getLong("revision"), rs.getInt("attempts"), Optional.empty()),
            leaseOwner));
  }

  /** Fails the bundles whose worker's lease expired on their last attempt, and returns them. */
  List<String> failExhaustedStaleJobs(int maxAttempts, Collection<String> definitions) {
    if (definitions.isEmpty()) {
      return List.of();
    }
    return jdbc.query("""
        update bundling.bundle_job
        set failure_message = 'The bundle was claimed ' || attempts || ' time(s) without recording '
                || 'a result and each lease expired; the attempt bound (' || :maxAttempts || ') is '
                || 'exhausted. The render most likely crashed; see the service logs.',
            lease_owner = null, lease_expires_at = null, next_attempt_at = null
        where failure_message is null and lease_expires_at <= now() and attempts >= :maxAttempts
          and definition in (:definitions)
        returning definition || ' of case ' || case_reference as bundle
        """,
        params("maxAttempts", maxAttempts, "definitions", definitions),
        (rs, n) -> rs.getString("bundle"));
  }

  /**
   * Records the bundle a worker rendered and releases its claim, in one statement. The row is
   * deleted, unless the bundle changed meanwhile: then the row stays, to be rendered again.
   * Nothing is written by a worker that lost its lease, or for a case that has gone.
   */
  boolean markCompleted(ClaimedBundleJob claimed, BundleRecord bundle) {
    return transaction.execute(status -> lockCase(claimed.job().caseReference())
        && recordCompleted(claimed, bundle));
  }

  private boolean recordCompleted(ClaimedBundleJob claimed, BundleRecord bundle) {
    return jdbc.update("""
        with up_to_date as (
          delete from bundling.bundle_job
        """ + CLAIMED + """
            and revision = :revision
          returning definition, case_reference
        ), changed as (
          update bundling.bundle_job set lease_owner = null, lease_expires_at = null
        """ + CLAIMED + """
            and revision <> :revision
          returning definition, case_reference
        )
        insert into bundling.bundle (definition, case_reference, outcome,
          file_name, media_type, size_bytes, sha256, page_count, request, documents,
          missing_documents, warnings, document_id, document_url, binary_url)
        select definition, case_reference, :outcome, :fileName, :mediaType, :size,
          :sha256, :pageCount, :request::jsonb, :documents::jsonb, :missingDocuments::jsonb,
          :warnings::jsonb, :documentId, :documentUrl, :binaryUrl
        from (select * from up_to_date union all select * from changed) released
        """,
        claimed(claimed)
            .addValue("outcome", bundle.outcome()).addValue("fileName", bundle.fileName())
            .addValue("mediaType", bundle.mediaType()).addValue("size", bundle.size())
            .addValue("sha256", bundle.sha256()).addValue("pageCount", bundle.pageCount())
            .addValue("request", bundle.requestJson()).addValue("documents", bundle.documentsJson())
            .addValue("missingDocuments", bundle.missingDocumentsJson())
            .addValue("warnings", bundle.warningsJson())
            .addValue("documentId", bundle.stored().id())
            .addValue("documentUrl", bundle.stored().url())
            .addValue("binaryUrl", bundle.stored().binaryUrl())) == 1;
  }

  /** A rendered bundle, serialised for bundling.bundle. */
  record BundleRecord(String outcome, String fileName, String mediaType, long size, String sha256,
      int pageCount, String requestJson, String documentsJson, String missingDocumentsJson,
      String warningsJson, StoredBundleDocument stored) {
  }

  /** The case's bundle recorded last: its renders run one at a time, so that is the current one. */
  public Optional<BundleMetadata> findLatestBundle(String definition, long caseReference) {
    return jdbc.query("""
        select id, definition, case_reference, created_at, outcome, file_name, media_type,
               size_bytes, sha256, page_count, request::text as request,
               documents::text as documents, missing_documents::text as missing_documents,
               warnings::text as warnings, document_id, document_url, binary_url
        from bundling.bundle
        where definition = :definition and case_reference = :caseReference
        order by created_at desc, id
        limit 1
        """, params("definition", definition, "caseReference", caseReference),
        (rs, n) -> new BundleMetadata(rs.getObject("id", UUID.class), rs.getString("definition"),
            rs.getLong("case_reference"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            json.read(rs.getString("request"), BundleRequest.class, "bundle request"),
            BundleOutcome.valueOf(rs.getString("outcome")), rs.getString("file_name"),
            rs.getString("media_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
            rs.getInt("page_count"),
            json.read(rs.getString("documents"), DOCUMENT_RESULTS, "bundle documents"),
            json.read(rs.getString("missing_documents"), MISSING_DOCUMENTS, "missing documents"),
            json.read(rs.getString("warnings"), WARNINGS, "bundle warnings"),
            new StoredBundleDocument(rs.getObject("document_id", UUID.class),
                rs.getString("document_url"), rs.getString("binary_url"))))
        .stream().findFirst();
  }

  /**
   * Stored PDFs of bundles that a bundle recorded later for the same case replaced at least
   * {@code replacedFor} ago.
   */
  List<UUID> findReplacedDocuments(Duration replacedFor, int limit) {
    return jdbc.query("""
        select document_id from bundling.bundle old
        where exists (select 1 from bundling.bundle newer
                      where newer.definition = old.definition
                        and newer.case_reference = old.case_reference
                        and newer.created_at > old.created_at
                        and newer.created_at <= now() - (:replacedMillis * interval '1 millisecond'))
        order by created_at
        limit :limit
        """, params("limit", limit, "replacedMillis", replacedFor.toMillis()),
        (rs, n) -> rs.getObject("document_id", UUID.class));
  }

  /** Deletes the record of a replaced bundle whose PDF has been deleted. */
  void deleteBundle(UUID documentId) {
    jdbc.update("delete from bundling.bundle where document_id = :documentId",
        params("documentId", documentId));
  }

  /** Fails the bundle, unless it changed meanwhile: then it is rendered again. */
  boolean markFailed(ClaimedBundleJob claimed, String message) {
    return jdbc.update("""
        update bundling.bundle_job
        set failure_message = case when revision = :revision then :message end,
            lease_owner = null, lease_expires_at = null, next_attempt_at = null
        """ + CLAIMED, claimed(claimed).addValue("message", message)) == 1;
  }

  /**
   * Queues another attempt, at once if the bundle changed meanwhile. A render that reports back is
   * not one that crashed, so it does not count towards the attempt bound.
   */
  boolean requeueForRetry(ClaimedBundleJob claimed, Instant nextAttemptAt) {
    return jdbc.update("""
        update bundling.bundle_job
        set next_attempt_at = case when revision = :revision then :nextAttemptAt::timestamptz end,
            attempts = 0, lease_owner = null, lease_expires_at = null
        """ + CLAIMED,
        claimed(claimed).addValue("nextAttemptAt",
            OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))) == 1;
  }

  private static MapSqlParameterSource claimed(ClaimedBundleJob claimed) {
    return params("definition", claimed.job().definition(),
        "caseReference", claimed.job().caseReference(), "revision", claimed.job().revision(),
        "leaseOwner", claimed.leaseOwner());
  }

  private static MapSqlParameterSource params(Object... keysAndValues) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      params.addValue((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return params;
  }
}
