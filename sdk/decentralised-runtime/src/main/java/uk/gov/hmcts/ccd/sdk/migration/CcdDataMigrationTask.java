package uk.gov.hmcts.ccd.sdk.migration;

import static uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationMode.CUTOVER;
import static uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationMode.PRELOAD_EVENTS;
import static uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationMode.VALIDATE_ONLY;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
public class CcdDataMigrationTask implements Runnable {
  private static final String DOCS_URL =
      "https://github.com/hmcts/dtsse-ccd-config-generator/blob/master/docs/fdw-data-migration.md";
  private static final String TARGET_SCHEMA = "ccd";
  private static final String FDW_SCHEMA = "fdw_stage";
  private static final String STATUS_PRELOAD = "PRELOAD";
  private static final String STATUS_CUTOVER = "CUTOVER";
  private static final String STATUS_COMPLETE = "COMPLETE";

  private final NamedParameterJdbcTemplate db;
  private final TransactionOperations transaction;
  private final CcdDataMigrationTaskOptions options;
  private final Clock clock;
  private final BooleanSupplier decentralisedRuntimeEnabled;

  public CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      PlatformTransactionManager transactionManager,
      CcdDataMigrationTaskOptions options
  ) {
    this(
        db,
        new TransactionTemplate(transactionManager),
        options,
        CcdDataMigrationTask::defaultDecentralisedRuntimeEnabled
    );
  }

  public CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      PlatformTransactionManager transactionManager,
      CcdDataMigrationTaskOptions options,
      Environment environment
  ) {
    this(db, new TransactionTemplate(transactionManager), options, decentralisedRuntimeEnabled(environment));
  }

  public CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      PlatformTransactionManager transactionManager,
      CcdDataMigrationTaskOptions options,
      BooleanSupplier decentralisedRuntimeEnabled
  ) {
    this(db, new TransactionTemplate(transactionManager), options, decentralisedRuntimeEnabled);
  }

  public CcdDataMigrationTask(NamedParameterJdbcTemplate db, CcdDataMigrationTaskOptions options) {
    this(
        db,
        new TransactionTemplate(defaultTransactionManager(db)),
        options,
        CcdDataMigrationTask::defaultDecentralisedRuntimeEnabled
    );
  }

  public CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      TransactionOperations transaction,
      CcdDataMigrationTaskOptions options
  ) {
    this(db, transaction, options, CcdDataMigrationTask::defaultDecentralisedRuntimeEnabled);
  }

  public CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      TransactionOperations transaction,
      CcdDataMigrationTaskOptions options,
      BooleanSupplier decentralisedRuntimeEnabled
  ) {
    this(db, transaction, options, Clock.systemUTC(), decentralisedRuntimeEnabled);
  }

  public CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      TransactionOperations transaction,
      CcdDataMigrationTaskOptions options,
      Clock clock,
      BooleanSupplier decentralisedRuntimeEnabled
  ) {
    this.db = Objects.requireNonNull(db, "db must not be null");
    this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
    this.options = Objects.requireNonNull(options, "options must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.decentralisedRuntimeEnabled = Objects.requireNonNull(
        decentralisedRuntimeEnabled,
        "decentralisedRuntimeEnabled must not be null"
    );
  }

  @Override
  public final void run() {
    runMigration();
  }

  public final CcdDataMigrationRunResult runMigration() {
    log.info(
        "Starting CCD data migration task taskName={} mode={} caseTypeIds={} eventBatchSize={} "
            + "maxBatchesPerRun={} maxRunTime={} settlementInterval={} validationMode={}",
        options.taskName(),
        options.mode(),
        options.caseTypeIds(),
        options.eventBatchSize(),
        options.maxBatchesPerRun(),
        options.maxRunTime(),
        options.settlementInterval(),
        options.validationMode()
    );

    validateDecentralisedRuntimeDisabled();
    validateProgressTableReady();
    AdvisoryLock migrationLock = tryLock();
    if (migrationLock == null) {
      log.warn("CCD data migration taskName={} is already running; skipping this invocation", options.taskName());
      return CcdDataMigrationRunResult.skippedLocked();
    }

    try {
      validateFdwReady();
      return switch (options.mode()) {
        case PRELOAD_EVENTS -> preloadEvents();
        case CUTOVER -> cutover();
        case VALIDATE_ONLY -> validateOnly();
      };
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new CcdDataMigrationException("CCD data migration failed", ex);
    } finally {
      migrationLock.close();
    }
  }

  private CcdDataMigrationRunResult preloadEvents() {
    Progress progress = getOrCreateProgress();
    if (!STATUS_PRELOAD.equals(progress.status())) {
      throw new CcdDataMigrationException(
          "PRELOAD_EVENTS cannot run when migration status is " + progress.status()
              + " taskName=" + options.taskName()
      );
    }

    long targetEventHwm = settledEventHighWaterMark();
    RunTotals totals = copyEventBatches(targetEventHwm);
    boolean caughtUp = loadProgress().loadedEventHwm() >= targetEventHwm;
    if (options.validationMode() == CcdDataMigrationValidationMode.ALWAYS) {
      validateNoMissingEventsUpTo(loadProgress().loadedEventHwm());
    }
    log.info(
        "Completed CCD data migration preload taskName={} targetEventHwm={} batches={} cases={} events={} "
            + "caughtUp={} stoppedByTimeLimit={}",
        options.taskName(),
        targetEventHwm,
        totals.batches(),
        totals.cases(),
        totals.events(),
        caughtUp,
        totals.stoppedByTimeLimit()
    );
    return new CcdDataMigrationRunResult(
        true,
        totals.batches(),
        totals.cases(),
        totals.events(),
        caughtUp,
        totals.stoppedByTimeLimit()
    );
  }

  private CcdDataMigrationRunResult cutover() {
    Progress progress = getOrCreateProgress();
    if (STATUS_COMPLETE.equals(progress.status())) {
      validateFinal(progress.requiredCutoverEventHwm());
      return new CcdDataMigrationRunResult(true, 0, 0, 0, true, false);
    }

    long cutoverEventHwm = progress.cutoverEventHwm() == null
        ? captureCutoverEventHighWaterMark()
        : progress.cutoverEventHwm();
    rebuildCasesWithMissingEvents(cutoverEventHwm);
    RunTotals totals = copyEventBatches(cutoverEventHwm);
    if (totals.stoppedByTimeLimit() || loadProgress().loadedEventHwm() < cutoverEventHwm) {
      log.info(
          "CCD data migration cutover paused before final refresh taskName={} cutoverEventHwm={} loadedEventHwm={} "
              + "batches={} events={} stoppedByTimeLimit={}",
          options.taskName(),
          cutoverEventHwm,
          loadProgress().loadedEventHwm(),
          totals.batches(),
          totals.events(),
          totals.stoppedByTimeLimit()
      );
      return new CcdDataMigrationRunResult(
          true,
          totals.batches(),
          totals.cases(),
          totals.events(),
          false,
          totals.stoppedByTimeLimit()
      );
    }
    int refreshedCases = refreshCutoverCaseData();
    resetSequences();
    validateFinal(cutoverEventHwm);
    markComplete();

    log.info(
        "Completed CCD data migration cutover taskName={} cutoverEventHwm={} batches={} refreshedCases={} events={} "
            + "stoppedByTimeLimit={}",
        options.taskName(),
        cutoverEventHwm,
        totals.batches(),
        refreshedCases,
        totals.events(),
        totals.stoppedByTimeLimit()
    );
    return new CcdDataMigrationRunResult(
        true,
        totals.batches(),
        refreshedCases,
        totals.events(),
        !totals.stoppedByTimeLimit(),
        totals.stoppedByTimeLimit()
    );
  }

  private CcdDataMigrationRunResult validateOnly() {
    Progress progress = getOrCreateProgress();
    long validationEventHwm = progress.cutoverEventHwm() == null
        ? sourceEventHighWaterMark()
        : progress.cutoverEventHwm();
    validateFinal(validationEventHwm);
    return new CcdDataMigrationRunResult(true, 0, 0, 0, true, false);
  }

  private RunTotals copyEventBatches(long targetEventHwm) {
    var totals = new RunTotals();
    LocalDateTime stopAt = calculateStopAt();

    for (int i = 0; i < options.maxBatchesPerRun(); i++) {
      Progress progress = loadProgress();
      if (progress.loadedEventHwm() >= targetEventHwm) {
        totals = totals.markCaughtUp();
        break;
      }

      List<Long> eventIds = findNextEventIds(progress.loadedEventHwm(), targetEventHwm);
      if (eventIds.isEmpty()) {
        totals = totals.markCaughtUp();
        break;
      }

      long batchUpperHwm = eventIds.get(eventIds.size() - 1);
      BatchResult batch = transaction.execute(status -> {
        validateNoConflictingCaseEvents(progress.loadedEventHwm(), batchUpperHwm);
        int cases = upsertParentCaseDataForEventRange(progress.loadedEventHwm(), batchUpperHwm);
        int events = insertCaseEvents(progress.loadedEventHwm(), batchUpperHwm);
        advanceLoadedEventHighWaterMark(batchUpperHwm);
        return new BatchResult(cases, events);
      });
      totals = totals.plus(batch);
      log.info(
          "CCD data migration progress taskName={} mode={} batchUpperHwm={} batchCases={} batchEvents={} "
              + "totalBatches={} totalCases={} totalEvents={}",
          options.taskName(),
          options.mode(),
          batchUpperHwm,
          batch.cases(),
          batch.events(),
          totals.batches(),
          totals.cases(),
          totals.events()
      );

      if (isTimeLimitReached(stopAt)) {
        log.info("Stopping CCD data migration taskName={} after event batch because time limit was reached",
            options.taskName());
        totals = totals.markStoppedByTimeLimit();
        break;
      }
    }
    return totals;
  }

  private List<Long> findNextEventIds(long loadedEventHwm, long targetEventHwm) {
    return db.query(
        """
        select ce.id
        from fdw_stage.case_event ce
        join fdw_stage.case_data cd on cd.id = ce.case_data_id
        where cd.case_type_id in (:caseTypeIds)
          and ce.id > :loadedEventHwm
          and ce.id <= :targetEventHwm
        order by ce.id
        limit :eventBatchSize
        """,
        baseParams()
            .addValue("loadedEventHwm", loadedEventHwm)
            .addValue("targetEventHwm", targetEventHwm)
            .addValue("eventBatchSize", options.eventBatchSize()),
        (rs, rowNum) -> rs.getLong("id")
    );
  }

  private int upsertParentCaseDataForEventRange(long loadedEventHwm, long batchUpperHwm) {
    return db.update(
        """
        with source_cases as materialized (
          select distinct on (cd.id)
            cd.reference,
            cd.version,
            cd.created_date,
            cd.security_classification,
            cd.last_state_modified_date,
            cd.resolved_ttl,
            cd.last_modified,
            cd.jurisdiction,
            cd.case_type_id,
            cd.state,
            cd.data,
            cd.supplementary_data,
            cd.id
          from fdw_stage.case_event ce
          join fdw_stage.case_data cd on cd.id = ce.case_data_id
          where cd.case_type_id in (:caseTypeIds)
            and ce.id > :loadedEventHwm
            and ce.id <= :batchUpperHwm
          order by cd.id
        )
        insert into ccd.case_data (
          reference,
          version,
          created_date,
          security_classification,
          last_state_modified_date,
          resolved_ttl,
          last_modified,
          jurisdiction,
          case_type_id,
          state,
          data,
          supplementary_data,
          id,
          case_revision
        )
        select
          cd.reference,
          cd.version,
          cd.created_date,
          cd.security_classification,
          cd.last_state_modified_date,
          cd.resolved_ttl,
          cd.last_modified,
          cd.jurisdiction,
          cd.case_type_id,
          cd.state,
          cd.data,
          coalesce(cd.supplementary_data, jsonb_build_object()),
          cd.id,
          0
        from source_cases cd
        on conflict (reference) do update
        set version = excluded.version,
            created_date = excluded.created_date,
            security_classification = excluded.security_classification,
            last_state_modified_date = excluded.last_state_modified_date,
            resolved_ttl = excluded.resolved_ttl,
            last_modified = excluded.last_modified,
            jurisdiction = excluded.jurisdiction,
            case_type_id = excluded.case_type_id,
            state = excluded.state,
            data = excluded.data,
            supplementary_data = excluded.supplementary_data,
            id = excluded.id
        """,
        eventRangeParams(loadedEventHwm, batchUpperHwm)
    );
  }

  private int insertCaseEvents(long loadedEventHwm, long batchUpperHwm) {
    return db.update(
        """
        with events_to_insert as (
          select ce.*
          from fdw_stage.case_event ce
          join fdw_stage.case_data cd on cd.id = ce.case_data_id
          where cd.case_type_id in (:caseTypeIds)
            and ce.id > :loadedEventHwm
            and ce.id <= :batchUpperHwm
        ),
        affected_cases as (
          select distinct case_data_id
          from events_to_insert
        ),
        ranked_source_events as (
          select ce.*,
                 row_number() over (
                   partition by ce.case_data_id
                   order by ce.id
                 ) as calculated_revision
          from fdw_stage.case_event ce
          join affected_cases ac on ac.case_data_id = ce.case_data_id
          where ce.id <= :batchUpperHwm
        )
        insert into ccd.case_event (
          id,
          created_date,
          security_classification,
          case_data_id,
          case_type_version,
          event_id,
          summary,
          description,
          user_id,
          case_type_id,
          state_id,
          data,
          user_first_name,
          user_last_name,
          event_name,
          state_name,
          proxied_by,
          proxied_by_first_name,
          proxied_by_last_name,
          idempotency_key,
          version,
          case_revision
        )
        select
          id,
          created_date,
          security_classification,
          case_data_id,
          case_type_version,
          event_id,
          summary,
          description,
          user_id,
          case_type_id,
          state_id,
          data,
          user_first_name,
          user_last_name,
          event_name,
          state_name,
          proxied_by,
          proxied_by_first_name,
          proxied_by_last_name,
          gen_random_uuid(),
          calculated_revision::int,
          calculated_revision::bigint
        from ranked_source_events
        where id > :loadedEventHwm
          and id <= :batchUpperHwm
        on conflict (id) do nothing
        """,
        eventRangeParams(loadedEventHwm, batchUpperHwm)
    );
  }

  private void validateNoConflictingCaseEvents(long loadedEventHwm, long batchUpperHwm) {
    assertNoSampleIds(
        "conflicting case_event rows already in the target",
        """
        select target.id
        from ccd.case_event target
        join fdw_stage.case_event source on source.id = target.id
        join fdw_stage.case_data cd on cd.id = source.case_data_id
        where cd.case_type_id in (:caseTypeIds)
          and source.id > :loadedEventHwm
          and source.id <= :batchUpperHwm
          and (
            target.created_date,
            target.security_classification,
            target.case_data_id,
            target.case_type_version,
            target.event_id,
            target.summary,
            target.description,
            target.user_id,
            target.case_type_id,
            target.state_id,
            target.data,
            target.user_first_name,
            target.user_last_name,
            target.event_name,
            target.state_name,
            target.proxied_by,
            target.proxied_by_first_name,
            target.proxied_by_last_name
          ) is distinct from (
            source.created_date,
            source.security_classification,
            source.case_data_id,
            source.case_type_version,
            source.event_id,
            source.summary,
            source.description,
            source.user_id,
            source.case_type_id,
            source.state_id,
            source.data,
            source.user_first_name,
            source.user_last_name,
            source.event_name,
            source.state_name,
            source.proxied_by,
            source.proxied_by_first_name,
            source.proxied_by_last_name
          )
        order by target.id
        limit 10
        """,
        eventRangeParams(loadedEventHwm, batchUpperHwm)
    );
  }

  private void rebuildCasesWithMissingEvents(long cutoverEventHwm) {
    List<Long> affectedCaseIds = db.query(
        """
        select distinct source_event.case_data_id
        from fdw_stage.case_event source_event
        join fdw_stage.case_data source_case on source_case.id = source_event.case_data_id
        left join ccd.case_event target on target.id = source_event.id
        where source_case.case_type_id in (:caseTypeIds)
          and source_event.id <= :loadedEventHwm
          and source_event.id <= :cutoverEventHwm
          and target.id is null
        order by source_event.case_data_id
        """,
        baseParams()
            .addValue("loadedEventHwm", loadProgress().loadedEventHwm())
            .addValue("cutoverEventHwm", cutoverEventHwm),
        (rs, rowNum) -> rs.getLong("case_data_id")
    );

    if (affectedCaseIds.isEmpty()) {
      return;
    }

    log.warn(
        "Rebuilding CCD migration event histories for cases with late lower-id events taskName={} caseDataIds={}",
        options.taskName(),
        affectedCaseIds
    );
    transaction.executeWithoutResult(status -> {
      upsertCaseDataByIds(affectedCaseIds, false);
      deleteTargetEventsForCases(affectedCaseIds);
      insertAllEventsForCases(affectedCaseIds, cutoverEventHwm);
    });
  }

  private int upsertCaseDataByIds(List<Long> caseDataIds, boolean finalRevision) {
    return db.update(
        """
        insert into ccd.case_data (
          reference,
          version,
          created_date,
          security_classification,
          last_state_modified_date,
          resolved_ttl,
          last_modified,
          jurisdiction,
          case_type_id,
          state,
          data,
          supplementary_data,
          id,
          case_revision
        )
        select
          cd.reference,
          cd.version,
          cd.created_date,
          cd.security_classification,
          cd.last_state_modified_date,
          cd.resolved_ttl,
          cd.last_modified,
          cd.jurisdiction,
          cd.case_type_id,
          cd.state,
          cd.data,
          coalesce(cd.supplementary_data, jsonb_build_object()),
          cd.id,
          case when :finalRevision then coalesce(event_counts.max_revision, 0) + :caseRevisionOffset else 0 end
        from fdw_stage.case_data cd
        left join (
          select case_data_id, max(case_revision)::bigint as max_revision
          from ccd.case_event
          where case_data_id in (:caseDataIds)
          group by case_data_id
        ) event_counts on event_counts.case_data_id = cd.id
        where cd.case_type_id in (:caseTypeIds)
          and cd.id in (:caseDataIds)
        on conflict (reference) do update
        set version = excluded.version,
            created_date = excluded.created_date,
            security_classification = excluded.security_classification,
            last_state_modified_date = excluded.last_state_modified_date,
            resolved_ttl = excluded.resolved_ttl,
            last_modified = excluded.last_modified,
            jurisdiction = excluded.jurisdiction,
            case_type_id = excluded.case_type_id,
            state = excluded.state,
            data = excluded.data,
            supplementary_data = excluded.supplementary_data,
            id = excluded.id,
            case_revision = excluded.case_revision
        """,
        baseParams()
            .addValue("caseDataIds", caseDataIds)
            .addValue("finalRevision", finalRevision)
            .addValue("caseRevisionOffset", options.caseRevisionOffset())
    );
  }

  private int deleteTargetEventsForCases(List<Long> caseDataIds) {
    return db.update(
        "delete from ccd.case_event where case_data_id in (:caseDataIds)",
        new MapSqlParameterSource().addValue("caseDataIds", caseDataIds)
    );
  }

  private int insertAllEventsForCases(List<Long> caseDataIds, long cutoverEventHwm) {
    return db.update(
        """
        with ranked_source_events as (
          select ce.*,
                 row_number() over (
                   partition by ce.case_data_id
                   order by ce.id
                 ) as calculated_revision
          from fdw_stage.case_event ce
          where ce.case_data_id in (:caseDataIds)
            and ce.id <= :cutoverEventHwm
        )
        insert into ccd.case_event (
          id,
          created_date,
          security_classification,
          case_data_id,
          case_type_version,
          event_id,
          summary,
          description,
          user_id,
          case_type_id,
          state_id,
          data,
          user_first_name,
          user_last_name,
          event_name,
          state_name,
          proxied_by,
          proxied_by_first_name,
          proxied_by_last_name,
          idempotency_key,
          version,
          case_revision
        )
        select
          id,
          created_date,
          security_classification,
          case_data_id,
          case_type_version,
          event_id,
          summary,
          description,
          user_id,
          case_type_id,
          state_id,
          data,
          user_first_name,
          user_last_name,
          event_name,
          state_name,
          proxied_by,
          proxied_by_first_name,
          proxied_by_last_name,
          gen_random_uuid(),
          calculated_revision::int,
          calculated_revision::bigint
        from ranked_source_events
        """,
        new MapSqlParameterSource()
            .addValue("caseDataIds", caseDataIds)
            .addValue("cutoverEventHwm", cutoverEventHwm)
    );
  }

  private int refreshCutoverCaseData() {
    return transaction.execute(status -> {
      disableCaseDataRevisionTrigger();
      try {
        int insertedMissingCases = insertMissingCaseDataForCutover();
        int revisedCases = updateCaseRevisionsForCutover();
        return insertedMissingCases + revisedCases;
      } finally {
        enableCaseDataRevisionTrigger();
      }
    });
  }

  private int insertMissingCaseDataForCutover() {
    return db.update(
        """
        insert into ccd.case_data (
          reference,
          version,
          created_date,
          security_classification,
          last_state_modified_date,
          resolved_ttl,
          last_modified,
          jurisdiction,
          case_type_id,
          state,
          data,
          supplementary_data,
          id,
          case_revision
        )
        select
          cd.reference,
          cd.version,
          cd.created_date,
          cd.security_classification,
          cd.last_state_modified_date,
          cd.resolved_ttl,
          cd.last_modified,
          cd.jurisdiction,
          cd.case_type_id,
          cd.state,
          cd.data,
          coalesce(cd.supplementary_data, jsonb_build_object()),
          cd.id,
          :caseRevisionOffset
        from fdw_stage.case_data cd
        left join ccd.case_data target on target.id = cd.id
        where cd.case_type_id in (:caseTypeIds)
          and target.id is null
        on conflict (reference) do nothing
        """,
        baseParams().addValue("caseRevisionOffset", options.caseRevisionOffset())
    );
  }

  private int updateCaseRevisionsForCutover() {
    return db.update(
        """
        with event_counts as (
          select case_data_id, max(case_revision)::bigint as max_revision
          from ccd.case_event
          where case_type_id in (:caseTypeIds)
          group by case_data_id
        )
        update ccd.case_data cd
        set case_revision = coalesce(event_counts.max_revision, 0) + :caseRevisionOffset
        from event_counts
        where cd.id = event_counts.case_data_id
          and cd.case_type_id in (:caseTypeIds)
        """,
        baseParams().addValue("caseRevisionOffset", options.caseRevisionOffset())
    );
  }

  private long settledEventHighWaterMark() {
    Long hwm = db.queryForObject(
        """
        select coalesce(max(ce.id), 0)
        from fdw_stage.case_event ce
        join fdw_stage.case_data cd on cd.id = ce.case_data_id
        where cd.case_type_id in (:caseTypeIds)
          and ce.created_date < clock_timestamp() - (:settlementMillis * interval '1 millisecond')
        """,
        baseParams().addValue("settlementMillis", options.settlementInterval().toMillis()),
        Long.class
    );
    return hwm == null ? 0 : hwm;
  }

  private long sourceEventHighWaterMark() {
    Long hwm = db.queryForObject(
        """
        select coalesce(max(ce.id), 0)
        from fdw_stage.case_event ce
        join fdw_stage.case_data cd on cd.id = ce.case_data_id
        where cd.case_type_id in (:caseTypeIds)
        """,
        baseParams(),
        Long.class
    );
    return hwm == null ? 0 : hwm;
  }

  private long captureCutoverEventHighWaterMark() {
    long hwm = sourceEventHighWaterMark();
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set status = :status,
            cutover_event_hwm = :cutoverEventHwm,
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of(
            "taskName", options.taskName(),
            "status", STATUS_CUTOVER,
            "cutoverEventHwm", hwm
        )
    );
    return hwm;
  }

  private void advanceLoadedEventHighWaterMark(long loadedEventHwm) {
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set loaded_event_hwm = :loadedEventHwm,
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of("taskName", options.taskName(), "loadedEventHwm", loadedEventHwm)
    );
  }

  private void markComplete() {
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set status = :status,
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of("taskName", options.taskName(), "status", STATUS_COMPLETE)
    );
  }

  private void validateFinal(long eventHwm) {
    validateNoMissingCases();
    validateCaseDataMatchesSource();
    validateNoMissingEventsUpTo(eventHwm);
    validateCaseEventsMatchSource(eventHwm);
    validateNoOrphans();
    validateCaseRevisionAlignment();
    validateEventHighWaterMark(eventHwm);
    finalValidation();
  }

  private void validateNoMissingCases() {
    assertNoSampleIds(
        "source case_data rows missing from target",
        """
        select source.id
        from fdw_stage.case_data source
        left join ccd.case_data target on target.id = source.id
        where source.case_type_id in (:caseTypeIds)
          and target.id is null
        order by source.id
        limit 10
        """,
        baseParams()
    );
  }

  private void validateCaseDataMatchesSource() {
    assertNoSampleIds(
        "source case_data rows different in target",
        """
        select source.id
        from fdw_stage.case_data source
        join ccd.case_data target on target.id = source.id
        where source.case_type_id in (:caseTypeIds)
          and (
            target.reference,
            target.version,
            target.created_date,
            target.security_classification,
            target.last_state_modified_date,
            target.resolved_ttl,
            target.last_modified,
            target.jurisdiction,
            target.case_type_id,
            target.state,
            target.data,
            target.supplementary_data
          ) is distinct from (
            source.reference,
            source.version,
            source.created_date,
            source.security_classification,
            source.last_state_modified_date,
            source.resolved_ttl,
            source.last_modified,
            source.jurisdiction,
            source.case_type_id,
            source.state,
            source.data,
            coalesce(source.supplementary_data, jsonb_build_object())
          )
        order by source.id
        limit 10
        """,
        baseParams()
    );
  }

  private void validateNoMissingEventsUpTo(long eventHwm) {
    assertNoSampleIds(
        "source case_event rows missing from target",
        """
        select source_event.id
        from fdw_stage.case_event source_event
        join fdw_stage.case_data source_case on source_case.id = source_event.case_data_id
        left join ccd.case_event target on target.id = source_event.id
        where source_case.case_type_id in (:caseTypeIds)
          and source_event.id <= :eventHwm
          and target.id is null
        order by source_event.id
        limit 10
        """,
        baseParams().addValue("eventHwm", eventHwm)
    );
  }

  private void validateCaseEventsMatchSource(long eventHwm) {
    assertNoSampleIds(
        "source case_event rows different in target",
        """
        select source_event.id
        from fdw_stage.case_event source_event
        join fdw_stage.case_data source_case on source_case.id = source_event.case_data_id
        join ccd.case_event target on target.id = source_event.id
        where source_case.case_type_id in (:caseTypeIds)
          and source_event.id <= :eventHwm
          and (
            target.created_date,
            target.security_classification,
            target.case_data_id,
            target.case_type_version,
            target.event_id,
            target.summary,
            target.description,
            target.user_id,
            target.case_type_id,
            target.state_id,
            target.data,
            target.user_first_name,
            target.user_last_name,
            target.event_name,
            target.state_name,
            target.proxied_by,
            target.proxied_by_first_name,
            target.proxied_by_last_name
          ) is distinct from (
            source_event.created_date,
            source_event.security_classification,
            source_event.case_data_id,
            source_event.case_type_version,
            source_event.event_id,
            source_event.summary,
            source_event.description,
            source_event.user_id,
            source_event.case_type_id,
            source_event.state_id,
            source_event.data,
            source_event.user_first_name,
            source_event.user_last_name,
            source_event.event_name,
            source_event.state_name,
            source_event.proxied_by,
            source_event.proxied_by_first_name,
            source_event.proxied_by_last_name
          )
        order by source_event.id
        limit 10
        """,
        baseParams().addValue("eventHwm", eventHwm)
    );
  }

  private void validateNoOrphans() {
    Long orphanCount = db.queryForObject(
        """
        select count(*)
        from ccd.case_event e
        left join ccd.case_data d on d.id = e.case_data_id
        where d.id is null
          and e.case_type_id in (:caseTypeIds)
        """,
        baseParams(),
        Long.class
    );
    if (orphanCount != null && orphanCount != 0) {
      throw new CcdDataMigrationException("Found " + orphanCount + " orphaned case_event rows");
    }
  }

  private void validateCaseRevisionAlignment() {
    Long mismatchCount = db.queryForObject(
        """
        with case_revision_check as (
          select cd.id,
                 cd.case_revision,
                 coalesce(max(ce.case_revision), 0)::bigint as max_event_revision,
                 coalesce(max(ce.case_revision), 0)::bigint + :caseRevisionOffset as expected_case_revision
          from ccd.case_data cd
          left join ccd.case_event ce on ce.case_data_id = cd.id
          where cd.case_type_id in (:caseTypeIds)
          group by cd.id, cd.case_revision
        )
        select count(*)
        from case_revision_check
        where case_revision is distinct from expected_case_revision
        """,
        baseParams().addValue("caseRevisionOffset", options.caseRevisionOffset()),
        Long.class
    );
    if (mismatchCount != null && mismatchCount != 0) {
      throw new CcdDataMigrationException("Found " + mismatchCount + " case_data.case_revision mismatches");
    }
  }

  private void validateEventHighWaterMark(long eventHwm) {
    Long targetMaxEventId = db.queryForObject(
        """
        select coalesce(max(ce.id), 0)
        from ccd.case_event ce
        join ccd.case_data cd on cd.id = ce.case_data_id
        where cd.case_type_id in (:caseTypeIds)
        """,
        baseParams(),
        Long.class
    );
    if (targetMaxEventId == null || targetMaxEventId < eventHwm) {
      throw new CcdDataMigrationException(
          "Target case_event high-water mark " + targetMaxEventId + " is below required " + eventHwm
      );
    }
  }

  private void finalValidation() {
    log.info("CCD data migration final counts taskName={} caseData={}", options.taskName(), queryCounts("case_data"));
    log.info("CCD data migration final counts taskName={} caseEvent={}", options.taskName(), queryCounts("case_event"));
  }

  private List<Map<String, Object>> queryCounts(String tableName) {
    String sql = switch (tableName) {
      case "case_data" -> """
          select case_type_id, count(*) as count
          from ccd.case_data
          where case_type_id in (:caseTypeIds)
          group by case_type_id
          order by case_type_id
          """;
      case "case_event" -> """
          select case_type_id, count(*) as count
          from ccd.case_event
          where case_type_id in (:caseTypeIds)
          group by case_type_id
          order by case_type_id
          """;
      default -> throw new IllegalArgumentException("Unsupported table name: " + tableName);
    };
    return db.queryForList(sql, baseParams());
  }

  private void resetSequences() {
    db.getJdbcTemplate().execute("""
        select setval(
          'ccd.case_event_id_seq'::regclass,
          (select coalesce(max(id), 1) from ccd.case_event),
          true
        )
        """);
  }

  private void disableCaseDataRevisionTrigger() {
    db.getJdbcTemplate().execute("alter table ccd.case_data disable trigger trigger_increment_case_revision");
  }

  private void enableCaseDataRevisionTrigger() {
    db.getJdbcTemplate().execute("alter table ccd.case_data enable trigger trigger_increment_case_revision");
  }

  private void validateProgressTableReady() {
    Integer columnCount = db.queryForObject(
        """
        select count(*)
        from information_schema.columns
        where table_schema = :schema
          and table_name = 'ccd_data_migration_progress'
          and column_name in (
            'task_name',
            'config_hash',
            'status',
            'loaded_event_hwm',
            'cutover_event_hwm',
            'created_at',
            'updated_at'
          )
        """,
        Map.of("schema", TARGET_SCHEMA),
        Integer.class
    );
    if (columnCount == null || columnCount != 7) {
      throw new CcdDataMigrationException(
          "CCD data migration progress table is missing or incomplete. "
              + "Run the decentralised-runtime Flyway migrations before starting the task."
      );
    }
  }

  private Progress getOrCreateProgress() {
    db.update(
        """
        insert into ccd.ccd_data_migration_progress (
          task_name,
          config_hash
        ) values (
          :taskName,
          :configHash
        )
        on conflict (task_name) do nothing
        """,
        progressConfigParams()
    );
    return loadProgress();
  }

  private Progress loadProgress() {
    Progress progress = db.queryForObject(
        """
        select config_hash,
               status,
               loaded_event_hwm,
               cutover_event_hwm
        from ccd.ccd_data_migration_progress
        where task_name = :taskName
        """,
        Map.of("taskName", options.taskName()),
        this::mapProgress
    );
    validateProgressConfiguration(progress);
    return progress;
  }

  private MapSqlParameterSource progressConfigParams() {
    return new MapSqlParameterSource()
        .addValue("taskName", options.taskName())
        .addValue("configHash", options.migrationConfigHash());
  }

  private void validateProgressConfiguration(Progress progress) {
    if (!options.migrationConfigHash().equals(progress.configHash())) {
      throw new CcdDataMigrationException(
          "CCD data migration progress for taskName=" + options.taskName()
              + " was created with a different migration configuration. Existing configuration hash: "
              + progress.configHash() + ". Current configuration: "
              + options.migrationConfigSummary()
              + ". Use a new taskName for a different migration, or reset ccd_data_migration_progress only "
              + "after confirming the existing migration state is no longer needed."
      );
    }
  }

  private Progress mapProgress(ResultSet rs, int rowNum) throws SQLException {
    Long cutoverEventHwm = rs.getObject("cutover_event_hwm") == null ? null : rs.getLong("cutover_event_hwm");
    return new Progress(
        rs.getString("config_hash"),
        rs.getString("status"),
        rs.getLong("loaded_event_hwm"),
        cutoverEventHwm
    );
  }

  private void validateFdwReady() {
    Integer pgcryptoCount = db.queryForObject(
        "select count(*) from pg_extension where extname = 'pgcrypto'",
        Map.of(),
        Integer.class
    );
    if (pgcryptoCount == null || pgcryptoCount != 1) {
      throw new CcdDataMigrationException("pgcrypto extension is missing. Run the FDW setup first: " + DOCS_URL);
    }

    Integer missingTables = db.queryForObject(
        """
        select 2 - count(*)
        from (
          select c.relname
          from pg_foreign_table ft
          join pg_class c on c.oid = ft.ftrelid
          join pg_namespace n on n.oid = c.relnamespace
          where n.nspname = :fdwSchema
            and c.relname in ('case_data', 'case_event')
        ) fdw_tables
        """,
        Map.of("fdwSchema", FDW_SCHEMA),
        Integer.class
    );
    if (missingTables == null || missingTables != 0) {
      throw new CcdDataMigrationException(
          "FDW foreign tables are missing in schema " + FDW_SCHEMA + ". Run the FDW setup first: " + DOCS_URL
      );
    }
  }

  private void validateDecentralisedRuntimeDisabled() {
    if (decentralisedRuntimeEnabled.getAsBoolean()) {
      throw new CcdDataMigrationException(
          "CCD data migration cannot run while the decentralised runtime is enabled. "
              + "Set ccd.sdk.decentralised=false or CCD_SDK_DECENTRALISED=false, and only run this task while live "
              + "case writes for the migrated case types still go to source CCD."
      );
    }
  }

  private LocalDateTime calculateStopAt() {
    return options.maxRunTime() == null ? null : LocalDateTime.now(clock).plus(options.maxRunTime());
  }

  private boolean isTimeLimitReached(LocalDateTime stopAt) {
    return stopAt != null && !LocalDateTime.now(clock).isBefore(stopAt);
  }

  private MapSqlParameterSource eventRangeParams(long loadedEventHwm, long batchUpperHwm) {
    return baseParams()
        .addValue("loadedEventHwm", loadedEventHwm)
        .addValue("batchUpperHwm", batchUpperHwm);
  }

  private MapSqlParameterSource baseParams() {
    return new MapSqlParameterSource().addValue("caseTypeIds", options.caseTypeIds());
  }

  private void assertNoSampleIds(String failure, String sql, MapSqlParameterSource params) {
    List<Long> ids = db.query(sql, params, (rs, rowNum) -> rs.getLong("id"));
    if (!ids.isEmpty()) {
      throw new CcdDataMigrationException("Found " + failure + " taskName=" + options.taskName() + " ids=" + ids);
    }
  }

  private AdvisoryLock tryLock() {
    DataSource dataSource = db.getJdbcTemplate().getDataSource();
    if (dataSource == null) {
      throw new CcdDataMigrationException("NamedParameterJdbcTemplate must expose a DataSource");
    }

    Connection connection = null;
    try {
      connection = dataSource.getConnection();
      try (PreparedStatement statement = connection.prepareStatement(
          "select pg_try_advisory_lock(hashtext(?), hashtext(?))"
      )) {
        statement.setString(1, options.taskName());
        statement.setString(2, options.canonicalCaseTypeIds());
        try (ResultSet result = statement.executeQuery()) {
          if (result.next() && result.getBoolean(1)) {
            return new AdvisoryLock(connection);
          }
        }
      }
      connection.close();
      return null;
    } catch (SQLException ex) {
      closeLockConnection(connection);
      throw new CcdDataMigrationException(
          "Could not acquire CCD data migration advisory lock taskName=" + options.taskName(),
          ex
      );
    }
  }

  private void closeLockConnection(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException closeEx) {
      log.warn("Failed to close CCD data migration lock connection taskName={}", options.taskName(), closeEx);
    }
  }

  private void abortLockConnection(Connection connection) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException abortEx) {
      log.warn("Failed to abort CCD data migration lock connection taskName={}", options.taskName(), abortEx);
      closeLockConnection(connection);
    }
  }

  private static PlatformTransactionManager defaultTransactionManager(NamedParameterJdbcTemplate db) {
    DataSource dataSource = db.getJdbcTemplate().getDataSource();
    if (dataSource == null) {
      throw new IllegalArgumentException("NamedParameterJdbcTemplate must expose a DataSource");
    }
    return new DataSourceTransactionManager(dataSource);
  }

  private static boolean defaultDecentralisedRuntimeEnabled() {
    return isTrue(System.getProperty("ccd.sdk.decentralised")) || isTrue(System.getenv("CCD_SDK_DECENTRALISED"));
  }

  private static BooleanSupplier decentralisedRuntimeEnabled(Environment environment) {
    Environment requiredEnvironment = Objects.requireNonNull(environment, "environment must not be null");
    return () -> requiredEnvironment.getProperty("ccd.sdk.decentralised", Boolean.class, false);
  }

  private static boolean isTrue(String value) {
    return value != null && Boolean.parseBoolean(value);
  }

  private final class AdvisoryLock implements AutoCloseable {
    private final Connection connection;

    private AdvisoryLock(Connection connection) {
      this.connection = connection;
    }

    @Override
    public void close() {
      boolean unlocked = false;
      try (PreparedStatement statement = connection.prepareStatement(
          "select pg_advisory_unlock(hashtext(?), hashtext(?))"
      )) {
        statement.setString(1, options.taskName());
        statement.setString(2, options.canonicalCaseTypeIds());
        try (ResultSet result = statement.executeQuery()) {
          unlocked = result.next() && result.getBoolean(1);
        }
      } catch (SQLException ex) {
        log.warn("Failed to release CCD data migration advisory lock taskName={}", options.taskName(), ex);
      } finally {
        if (unlocked) {
          closeLockConnection(connection);
        } else {
          abortLockConnection(connection);
        }
      }
    }
  }

  private record Progress(String configHash, String status, long loadedEventHwm, Long cutoverEventHwm) {
    long requiredCutoverEventHwm() {
      if (cutoverEventHwm == null) {
        throw new CcdDataMigrationException("cutover_event_hwm is not set for completed migration");
      }
      return cutoverEventHwm;
    }
  }

  private record BatchResult(int cases, int events) {
  }

  private record RunTotals(long batches, long cases, long events, boolean caughtUp, boolean stoppedByTimeLimit) {
    private RunTotals() {
      this(0, 0, 0, false, false);
    }

    private RunTotals plus(BatchResult result) {
      return new RunTotals(batches + 1, cases + result.cases(), events + result.events(), caughtUp, stoppedByTimeLimit);
    }

    private RunTotals markCaughtUp() {
      return new RunTotals(batches, cases, events, true, stoppedByTimeLimit);
    }

    private RunTotals markStoppedByTimeLimit() {
      return new RunTotals(batches, cases, events, caughtUp, true);
    }
  }
}
