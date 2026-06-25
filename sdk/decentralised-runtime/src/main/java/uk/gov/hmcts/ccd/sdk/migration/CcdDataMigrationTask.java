package uk.gov.hmcts.ccd.sdk.migration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
public abstract class CcdDataMigrationTask implements Runnable {
  private static final String DOCS_URL =
      "https://github.com/hmcts/dtsse-ccd-config-generator/blob/master/docs/fdw-data-migration.md";
  private static final String INITIAL_PHASE = "INITIAL";
  private static final String DELTA_PHASE = "DELTA";

  private final NamedParameterJdbcTemplate db;
  private final TransactionOperations transaction;
  private final CcdDataMigrationTaskOptions options;
  private final Clock clock;
  private final String targetSchema;
  private final String fdwSchema;
  private final String progressTable;

  protected CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      PlatformTransactionManager transactionManager,
      CcdDataMigrationTaskOptions options
  ) {
    this(db, new TransactionTemplate(transactionManager), options);
  }

  protected CcdDataMigrationTask(NamedParameterJdbcTemplate db, CcdDataMigrationTaskOptions options) {
    this(db, new TransactionTemplate(defaultTransactionManager(db)), options);
  }

  protected CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      TransactionOperations transaction,
      CcdDataMigrationTaskOptions options
  ) {
    this(db, transaction, options, Clock.systemUTC());
  }

  protected CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      TransactionOperations transaction,
      CcdDataMigrationTaskOptions options,
      Clock clock
  ) {
    this.db = Objects.requireNonNull(db, "db must not be null");
    this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
    this.options = Objects.requireNonNull(options, "options must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.targetSchema = quoteIdentifier(options.targetSchema());
    this.fdwSchema = quoteIdentifier(options.fdwSchema());
    this.progressTable = targetSchema + ".ccd_data_migration_progress";
  }

  @Override
  public final void run() {
    runMigration();
  }

  public final CcdDataMigrationRunResult runMigration() {
    log.info(
        "Starting CCD data migration task taskName={} caseTypeIds={} batchSize={} maxBatchesPerRun={} "
            + "maxRunTime={} runUntil={}",
        options.taskName(),
        options.caseTypeIds(),
        options.batchSize(),
        options.maxBatchesPerRun(),
        options.maxRunTime(),
        options.runUntil()
    );

    ensureProgressTable();
    if (!tryLock()) {
      log.warn("CCD data migration taskName={} is already running; skipping this invocation", options.taskName());
      return CcdDataMigrationRunResult.skippedLocked();
    }

    var constraintsPrepared = false;
    try {
      validateCanDisableTriggers();
      validateFdwReady();
      prepareTarget();
      constraintsPrepared = true;

      RunTotals totals = processAvailableBatches();
      finishMigration();
      constraintsPrepared = false;

      log.info(
          "Completed CCD data migration taskName={} batches={} cases={} events={} caughtUp={} stoppedByTimeLimit={}",
          options.taskName(),
          totals.batches(),
          totals.cases(),
          totals.events(),
          totals.caughtUp(),
          totals.stoppedByTimeLimit()
      );

      return new CcdDataMigrationRunResult(
          true,
          totals.batches(),
          totals.cases(),
          totals.events(),
          totals.caughtUp(),
          totals.stoppedByTimeLimit()
      );
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new CcdDataMigrationException("CCD data migration failed", ex);
    } finally {
      if (constraintsPrepared) {
        restoreConstraintsAfterFailure();
      }
      unlock();
    }
  }

  private void finishMigration() {
    validateNoOrphans();
    restoreConstraints();
    resetSequences();
    finalValidation();
    validateCaseRevisionAlignment();
  }

  protected CcdDataMigrationTaskOptions options() {
    return options;
  }

  private static PlatformTransactionManager defaultTransactionManager(NamedParameterJdbcTemplate db) {
    DataSource dataSource = db.getJdbcTemplate().getDataSource();
    if (dataSource == null) {
      throw new IllegalArgumentException("NamedParameterJdbcTemplate must expose a DataSource");
    }
    return new DataSourceTransactionManager(dataSource);
  }

  private void ensureProgressTable() {
    db.getJdbcTemplate().execute("""
        create table if not exists %s (
          task_name varchar(255) primary key,
          phase varchar(20) not null,
          window_start timestamp without time zone,
          window_end timestamp without time zone not null,
          last_case_data_id bigint not null default 0,
          initial_complete boolean not null default false,
          total_batches bigint not null default 0,
          total_cases bigint not null default 0,
          total_events bigint not null default 0,
          created_at timestamp without time zone not null default (now() at time zone 'UTC'),
          updated_at timestamp without time zone not null default (now() at time zone 'UTC')
        )
        """.formatted(progressTable));
  }

  private boolean tryLock() {
    Boolean locked = db.queryForObject(
        "select pg_try_advisory_lock(hashtext(:taskName), hashtext(:caseTypes))",
        lockParams(),
        Boolean.class
    );
    return Boolean.TRUE.equals(locked);
  }

  private void unlock() {
    try {
      db.queryForObject(
          "select pg_advisory_unlock(hashtext(:taskName), hashtext(:caseTypes))",
          lockParams(),
          Boolean.class
      );
    } catch (RuntimeException ex) {
      log.warn("Failed to release CCD data migration advisory lock taskName={}", options.taskName(), ex);
    }
  }

  private Map<String, Object> lockParams() {
    return Map.of(
        "taskName", options.taskName(),
        "caseTypes", String.join(",", options.caseTypeIds())
    );
  }

  private void validateCanDisableTriggers() {
    transaction.executeWithoutResult(status -> {
      db.getJdbcTemplate().execute("set local session_replication_role = replica");
      db.getJdbcTemplate().execute("reset session_replication_role");
    });
  }

  private void validateFdwReady() {
    Integer pgcryptoCount = db.queryForObject(
        "select count(*) from pg_extension where extname = 'pgcrypto'",
        Map.of(),
        Integer.class
    );
    if (pgcryptoCount == null || pgcryptoCount != 1) {
      throw new CcdDataMigrationException(
          "pgcrypto extension is missing. Run the FDW setup first: " + DOCS_URL
      );
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
        Map.of("fdwSchema", options.fdwSchema()),
        Integer.class
    );
    if (missingTables == null || missingTables != 0) {
      throw new CcdDataMigrationException(
          "FDW foreign tables are missing in schema " + options.fdwSchema()
              + ". Run the FDW setup first: " + DOCS_URL
      );
    }
  }

  private void prepareTarget() {
    log.info("Preparing CCD migration target tables taskName={}", options.taskName());
    db.getJdbcTemplate().execute("""
        alter table %s.case_event
        drop constraint if exists case_event_case_data_id_fkey
        """.formatted(targetSchema));
    db.getJdbcTemplate().execute("""
        drop index if exists %s.idx_case_event_case_data_revision_unique
        """.formatted(targetSchema));
    db.getJdbcTemplate().execute("""
        alter table %s.case_event
        disable trigger user
        """.formatted(targetSchema));
  }

  private RunTotals processAvailableBatches() {
    var totals = new RunTotals();
    Progress progress = getOrCreateProgress();
    LocalDateTime stopAt = calculateStopAt();

    for (int i = 0; i < options.maxBatchesPerRun(); i++) {
      progress = prepareDeltaWindow(progress);
      List<Long> caseDataIds = findNextCaseDataIds(progress);

      if (caseDataIds.isEmpty()) {
        progress = completeCurrentWindow(progress);
        if (DELTA_PHASE.equals(progress.phase())) {
          totals = totals.markCaughtUp();
          break;
        }
        continue;
      }

      BatchResult batch = migrateBatch(caseDataIds);
      totals = totals.plus(batch);
      progress = recordBatch(progress, caseDataIds, batch);
      log.info(
          "CCD data migration progress taskName={} phase={} batchCases={} batchEvents={} "
              + "lastCaseDataId={} totalBatches={} totalCases={} totalEvents={}",
          options.taskName(),
          progress.phase(),
          batch.cases(),
          batch.events(),
          progress.lastCaseDataId(),
          progress.totalBatches(),
          progress.totalCases(),
          progress.totalEvents()
      );

      if (isTimeLimitReached(stopAt)) {
        log.info(
            "Stopping CCD data migration taskName={} after chunk because time limit was reached",
            options.taskName()
        );
        totals = totals.markStoppedByTimeLimit();
        break;
      }
    }

    return totals;
  }

  private LocalDateTime calculateStopAt() {
    LocalDateTime maxRuntimeStopAt = options.maxRunTime() == null
        ? null
        : LocalDateTime.now(clock).plus(options.maxRunTime());

    if (maxRuntimeStopAt == null) {
      return options.runUntil();
    }
    if (options.runUntil() == null) {
      return maxRuntimeStopAt;
    }
    return maxRuntimeStopAt.isBefore(options.runUntil()) ? maxRuntimeStopAt : options.runUntil();
  }

  private boolean isTimeLimitReached(LocalDateTime stopAt) {
    return stopAt != null && !LocalDateTime.now(clock).isBefore(stopAt);
  }

  private Progress getOrCreateProgress() {
    db.update(
        """
        insert into %s (
          task_name,
          phase,
          window_end
        ) values (
          :taskName,
          :phase,
          now() at time zone 'UTC'
        )
        on conflict (task_name) do nothing
        """.formatted(progressTable),
        Map.of("taskName", options.taskName(), "phase", INITIAL_PHASE)
    );
    return loadProgress();
  }

  private Progress loadProgress() {
    return db.queryForObject(
        """
        select task_name,
               phase,
               window_start,
               window_end,
               last_case_data_id,
               initial_complete,
               total_batches,
               total_cases,
               total_events
        from %s
        where task_name = :taskName
        """.formatted(progressTable),
        Map.of("taskName", options.taskName()),
        this::mapProgress
    );
  }

  private Progress prepareDeltaWindow(Progress progress) {
    if (!DELTA_PHASE.equals(progress.phase()) || !progress.windowStart().equals(progress.windowEnd())) {
      return progress;
    }

    db.update(
        """
        update %s
        set window_end = now() at time zone 'UTC',
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """.formatted(progressTable),
        Map.of("taskName", options.taskName())
    );
    return loadProgress();
  }

  private List<Long> findNextCaseDataIds(Progress progress) {
    var params = new MapSqlParameterSource()
        .addValue("caseTypeIds", options.caseTypeIds())
        .addValue("lastCaseDataId", progress.lastCaseDataId())
        .addValue("windowStart", progress.windowStart())
        .addValue("windowEnd", progress.windowEnd())
        .addValue("batchSize", options.batchSize());

    var deltaPredicate = DELTA_PHASE.equals(progress.phase())
        ? "and coalesce(last_modified, created_date) >= :windowStart"
        : "";

    return db.queryForList(
        """
        select id
        from %s.case_data
        where case_type_id in (:caseTypeIds)
          and id > :lastCaseDataId
          and coalesce(last_modified, created_date) < :windowEnd
          %s
        order by id
        limit :batchSize
        """.formatted(fdwSchema, deltaPredicate),
        params,
        Long.class
    );
  }

  private Progress completeCurrentWindow(Progress progress) {
    if (INITIAL_PHASE.equals(progress.phase())) {
      log.info(
          "CCD data migration initial load complete taskName={} initialWindowEnd={}",
          options.taskName(),
          progress.windowEnd()
      );
      db.update(
          """
          update %s
          set phase = :phase,
              initial_complete = true,
              window_start = window_end,
              window_end = now() at time zone 'UTC',
              last_case_data_id = 0,
              updated_at = now() at time zone 'UTC'
          where task_name = :taskName
          """.formatted(progressTable),
          Map.of("taskName", options.taskName(), "phase", DELTA_PHASE)
      );
    } else {
      log.info(
          "CCD data migration caught up taskName={} deltaWindowEnd={}",
          options.taskName(),
          progress.windowEnd()
      );
      db.update(
          """
          update %s
          set window_start = window_end,
              last_case_data_id = 0,
              updated_at = now() at time zone 'UTC'
          where task_name = :taskName
          """.formatted(progressTable),
          Map.of("taskName", options.taskName())
      );
    }
    return loadProgress();
  }

  private BatchResult migrateBatch(List<Long> caseDataIds) {
    return transaction.execute(status -> {
      db.getJdbcTemplate().execute("set local session_replication_role = replica");
      int cases = loadCaseData(caseDataIds);
      int events = loadCaseEvents(caseDataIds);
      recalculateRevisions(caseDataIds);
      return new BatchResult(cases, events);
    });
  }

  private int loadCaseData(List<Long> caseDataIds) {
    return db.update(
        """
        insert into %s.case_data (
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
          coalesce(supplementary_data, jsonb_build_object()),
          id,
          version
        from %s.case_data
        where id in (:caseDataIds)
          and case_type_id in (:caseTypeIds)
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
        where (
          %s.case_data.version,
          %s.case_data.last_modified,
          %s.case_data.case_revision,
          %s.case_data.state,
          %s.case_data.data,
          %s.case_data.supplementary_data
        ) is distinct from (
          excluded.version,
          excluded.last_modified,
          excluded.case_revision,
          excluded.state,
          excluded.data,
          excluded.supplementary_data
        )
        """.formatted(targetSchema, fdwSchema, targetSchema, targetSchema, targetSchema, targetSchema, targetSchema,
            targetSchema),
        batchParams(caseDataIds)
    );
  }

  private int loadCaseEvents(List<Long> caseDataIds) {
    return db.update(
        """
        insert into %s.case_event (
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
          ce.id,
          ce.created_date,
          ce.security_classification,
          ce.case_data_id,
          ce.case_type_version,
          ce.event_id,
          ce.summary,
          ce.description,
          ce.user_id,
          ce.case_type_id,
          ce.state_id,
          ce.data,
          ce.user_first_name,
          ce.user_last_name,
          ce.event_name,
          ce.state_name,
          ce.proxied_by,
          ce.proxied_by_first_name,
          ce.proxied_by_last_name,
          coalesce(ce.idempotency_key, gen_random_uuid()),
          0,
          0
        from %s.case_event ce
        join %s.case_data cd on cd.id = ce.case_data_id
        where ce.case_data_id in (:caseDataIds)
          and cd.case_type_id in (:caseTypeIds)
          and ce.case_type_id in (:caseTypeIds)
        on conflict (id) do update
        set created_date = excluded.created_date,
            security_classification = excluded.security_classification,
            case_data_id = excluded.case_data_id,
            case_type_version = excluded.case_type_version,
            event_id = excluded.event_id,
            summary = excluded.summary,
            description = excluded.description,
            user_id = excluded.user_id,
            case_type_id = excluded.case_type_id,
            state_id = excluded.state_id,
            data = excluded.data,
            user_first_name = excluded.user_first_name,
            user_last_name = excluded.user_last_name,
            event_name = excluded.event_name,
            state_name = excluded.state_name,
            proxied_by = excluded.proxied_by,
            proxied_by_first_name = excluded.proxied_by_first_name,
            proxied_by_last_name = excluded.proxied_by_last_name
        """.formatted(targetSchema, fdwSchema, targetSchema),
        batchParams(caseDataIds)
    );
  }

  private void recalculateRevisions(List<Long> caseDataIds) {
    db.getJdbcTemplate().execute("""
        create index if not exists idx_tmp_case_event_case_data_id_id
        on %s.case_event (case_data_id, id)
        """.formatted(targetSchema));

    db.update(
        """
        update %s.case_event target
        set version = source.revision::int,
            case_revision = source.revision::bigint
        from (
          select id,
                 row_number() over (
                   partition by case_data_id
                   order by id
                 ) as revision
          from %s.case_event
          where case_data_id in (:caseDataIds)
        ) source
        where target.id = source.id
        """.formatted(targetSchema, targetSchema),
        batchParams(caseDataIds)
    );

    db.update(
        """
        update %s.case_data cd
        set case_revision = counts.event_count + :caseRevisionOffset
        from (
          select cd2.id,
                 count(ce.id)::bigint as event_count
          from %s.case_data cd2
          left join %s.case_event ce on ce.case_data_id = cd2.id
          where cd2.id in (:caseDataIds)
          group by cd2.id
        ) counts
        where cd.id = counts.id
        """.formatted(targetSchema, targetSchema, targetSchema),
        batchParams(caseDataIds).addValue("caseRevisionOffset", options.caseRevisionOffset())
    );
  }

  private Progress recordBatch(Progress progress, List<Long> caseDataIds, BatchResult batch) {
    long lastCaseDataId = caseDataIds.get(caseDataIds.size() - 1);
    db.update(
        """
        update %s
        set last_case_data_id = :lastCaseDataId,
            total_batches = total_batches + 1,
            total_cases = total_cases + :cases,
            total_events = total_events + :events,
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """.formatted(progressTable),
        Map.of(
            "taskName", options.taskName(),
            "lastCaseDataId", lastCaseDataId,
            "cases", batch.cases(),
            "events", batch.events()
        )
    );
    return loadProgress();
  }

  private void validateNoOrphans() {
    Long orphanCount = db.queryForObject(
        """
        select count(*)
        from %s.case_event e
        left join %s.case_data d on d.id = e.case_data_id
        where d.id is null
          and e.case_type_id in (:caseTypeIds)
        """.formatted(targetSchema, targetSchema),
        baseParams(),
        Long.class
    );
    if (orphanCount != null && orphanCount != 0) {
      throw new CcdDataMigrationException("Found " + orphanCount + " orphaned case_event rows");
    }
  }

  private void restoreConstraints() {
    log.info("Restoring CCD migration target constraints taskName={}", options.taskName());
    db.getJdbcTemplate().execute("""
        create unique index if not exists idx_case_event_case_data_revision_unique
        on %s.case_event (case_data_id, case_revision)
        """.formatted(targetSchema));
    db.getJdbcTemplate().execute("""
        do $$
        begin
          if not exists (
            select 1
            from pg_constraint
            where conname = 'case_event_case_data_id_fkey'
              and conrelid = '%s.case_event'::regclass
          ) then
            alter table %s.case_event
            add constraint case_event_case_data_id_fkey
            foreign key (case_data_id)
            references %s.case_data(id)
            on delete cascade;
          end if;
        end $$;
        """.formatted(targetSchema, targetSchema, targetSchema));
    db.getJdbcTemplate().execute("""
        alter table %s.case_event
        enable trigger user
        """.formatted(targetSchema));
  }

  private void restoreConstraintsAfterFailure() {
    try {
      restoreConstraints();
    } catch (RuntimeException ex) {
      log.error("Failed to restore CCD migration constraints taskName={}", options.taskName(), ex);
    }
  }

  private void resetSequences() {
    db.getJdbcTemplate().execute("""
        select setval(
          format('%%I.case_event_id_seq', '%s')::regclass,
          (select coalesce(max(id), 1) from %s.case_event),
          true
        )
        """.formatted(options.targetSchema(), targetSchema));
  }

  private void finalValidation() {
    log.info("CCD data migration final counts taskName={} caseData={}", options.taskName(), queryCounts("case_data"));
    log.info("CCD data migration final counts taskName={} caseEvent={}", options.taskName(), queryCounts("case_event"));
  }

  private List<Map<String, Object>> queryCounts(String tableName) {
    return db.queryForList(
        """
        select case_type_id, count(*) as count
        from %s.%s
        where case_type_id in (:caseTypeIds)
        group by case_type_id
        order by case_type_id
        """.formatted(targetSchema, tableName),
        baseParams()
    );
  }

  private void validateCaseRevisionAlignment() {
    Long mismatchCount = db.queryForObject(
        """
        with case_revision_check as (
          select cd.id,
                 cd.case_revision,
                 count(ce.id)::bigint as event_count,
                 coalesce(max(ce.case_revision), 0)::bigint as max_event_revision,
                 coalesce(max(ce.case_revision), 0)::bigint + :caseRevisionOffset as expected_case_revision
          from %s.case_data cd
          left join %s.case_event ce on ce.case_data_id = cd.id
          where cd.case_type_id in (:caseTypeIds)
          group by cd.id, cd.case_revision
        )
        select count(*)
        from case_revision_check
        where case_revision is distinct from expected_case_revision
           or event_count is distinct from max_event_revision
        """.formatted(targetSchema, targetSchema),
        baseParams().addValue("caseRevisionOffset", options.caseRevisionOffset()),
        Long.class
    );
    if (mismatchCount != null && mismatchCount != 0) {
      throw new CcdDataMigrationException("Found " + mismatchCount + " case_data.case_revision mismatches");
    }
  }

  private MapSqlParameterSource batchParams(List<Long> caseDataIds) {
    return baseParams().addValue("caseDataIds", caseDataIds);
  }

  private MapSqlParameterSource baseParams() {
    return new MapSqlParameterSource()
        .addValue("caseTypeIds", options.caseTypeIds());
  }

  private Progress mapProgress(ResultSet rs, int rowNum) throws SQLException {
    return new Progress(
        rs.getString("phase"),
        rs.getObject("window_start", LocalDateTime.class),
        rs.getObject("window_end", LocalDateTime.class),
        rs.getLong("last_case_data_id"),
        rs.getBoolean("initial_complete"),
        rs.getLong("total_batches"),
        rs.getLong("total_cases"),
        rs.getLong("total_events")
    );
  }

  private static String quoteIdentifier(String identifier) {
    CcdDataMigrationTaskOptions.requireIdentifier(identifier, "identifier");
    return "\"" + identifier + "\"";
  }

  private record Progress(
      String phase,
      LocalDateTime windowStart,
      LocalDateTime windowEnd,
      long lastCaseDataId,
      boolean initialComplete,
      long totalBatches,
      long totalCases,
      long totalEvents
  ) {
  }

  private record BatchResult(int cases, int events) {
  }

  private record RunTotals(long batches, long cases, long events, boolean caughtUp, boolean stoppedByTimeLimit) {
    private RunTotals() {
      this(0, 0, 0, false, false);
    }

    RunTotals plus(BatchResult batch) {
      return new RunTotals(
          batches + 1,
          cases + batch.cases(),
          events + batch.events(),
          caughtUp,
          stoppedByTimeLimit
      );
    }

    RunTotals markCaughtUp() {
      return new RunTotals(batches, cases, events, true, stoppedByTimeLimit);
    }

    RunTotals markStoppedByTimeLimit() {
      return new RunTotals(batches, cases, events, caughtUp, true);
    }
  }
}
