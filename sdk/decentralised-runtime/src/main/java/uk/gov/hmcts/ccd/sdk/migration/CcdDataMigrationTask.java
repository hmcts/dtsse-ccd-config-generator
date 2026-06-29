package uk.gov.hmcts.ccd.sdk.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
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
public abstract class CcdDataMigrationTask implements Runnable {
  private static final String DOCS_URL =
      "https://github.com/hmcts/dtsse-ccd-config-generator/blob/master/docs/fdw-data-migration.md";
  private static final String INITIAL_PHASE = "INITIAL";
  private static final String DELTA_PHASE = "DELTA";
  private static final String TARGET_SCHEMA = "ccd";
  private static final String FDW_SCHEMA = "fdw_stage";

  private final NamedParameterJdbcTemplate db;
  private final TransactionOperations transaction;
  private final CcdDataMigrationTaskOptions options;
  private final Clock clock;
  private final BooleanSupplier decentralisedRuntimeEnabled;

  protected CcdDataMigrationTask(
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

  protected CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      PlatformTransactionManager transactionManager,
      CcdDataMigrationTaskOptions options,
      Environment environment
  ) {
    this(
        db,
        new TransactionTemplate(transactionManager),
        options,
        decentralisedRuntimeEnabled(environment)
    );
  }

  protected CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      PlatformTransactionManager transactionManager,
      CcdDataMigrationTaskOptions options,
      BooleanSupplier decentralisedRuntimeEnabled
  ) {
    this(db, new TransactionTemplate(transactionManager), options, decentralisedRuntimeEnabled);
  }

  protected CcdDataMigrationTask(NamedParameterJdbcTemplate db, CcdDataMigrationTaskOptions options) {
    this(
        db,
        new TransactionTemplate(defaultTransactionManager(db)),
        options,
        CcdDataMigrationTask::defaultDecentralisedRuntimeEnabled
    );
  }

  protected CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      TransactionOperations transaction,
      CcdDataMigrationTaskOptions options
  ) {
    this(db, transaction, options, CcdDataMigrationTask::defaultDecentralisedRuntimeEnabled);
  }

  protected CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      TransactionOperations transaction,
      CcdDataMigrationTaskOptions options,
      BooleanSupplier decentralisedRuntimeEnabled
  ) {
    this(db, transaction, options, Clock.systemUTC(), decentralisedRuntimeEnabled);
  }

  protected CcdDataMigrationTask(
      NamedParameterJdbcTemplate db,
      TransactionOperations transaction,
      CcdDataMigrationTaskOptions options,
      Clock clock
  ) {
    this(db, transaction, options, clock, CcdDataMigrationTask::defaultDecentralisedRuntimeEnabled);
  }

  protected CcdDataMigrationTask(
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
        "Starting CCD data migration task taskName={} caseTypeIds={} batchSize={} maxBatchesPerRun={} "
            + "maxRunTime={} runUntil={} deltaOverlap={} validationMode={}",
        options.taskName(),
        options.caseTypeIds(),
        options.batchSize(),
        options.maxBatchesPerRun(),
        options.maxRunTime(),
        options.runUntil(),
        options.deltaOverlap(),
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

      RunTotals totals = processAvailableBatches();
      Progress progress = loadProgress();
      if (totals.caughtUp() && progress.targetPrepared()) {
        restoreTargetAfterCatchUp();
        progress = loadProgress();
      }
      runFullValidationIfEnabled(progress);

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
      migrationLock.close();
    }
  }

  private void restoreTargetAfterCatchUp() {
    restoreConstraints();
    markTargetRestored();
    resetSequences();
  }

  private void runFullValidationIfEnabled(Progress progress) {
    if (!shouldRunFullValidation(progress)) {
      log.info(
          "Skipping CCD data migration full validation taskName={} validationMode={} phase={}",
          options.taskName(),
          options.validationMode(),
          progress.phase()
      );
      return;
    }

    validateNoOrphans();
    finalValidation();
    validateCaseRevisionAlignment();
  }

  private boolean shouldRunFullValidation(Progress progress) {
    return switch (options.validationMode()) {
      case ALWAYS -> true;
      case NEVER -> false;
      case DELTA_ONLY -> DELTA_PHASE.equals(progress.phase());
    };
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

  private void validateDecentralisedRuntimeDisabled() {
    if (decentralisedRuntimeEnabled.getAsBoolean()) {
      throw new CcdDataMigrationException(
          "CCD data migration cannot run while the decentralised runtime is enabled. "
              + "Set ccd.sdk.decentralised=false or CCD_SDK_DECENTRALISED=false, and only run this task while live "
              + "case writes for the migrated case types still go to source CCD."
      );
    }
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
            'target_schema',
            'fdw_schema',
            'case_type_ids',
            'case_revision_offset',
            'phase',
            'window_start',
            'window_end',
            'last_case_data_modified',
            'last_case_data_id',
            'initial_complete',
            'total_batches',
            'total_cases',
            'total_events',
            'target_prepared',
            'created_at',
            'updated_at'
          )
        """,
        Map.of("schema", TARGET_SCHEMA),
        Integer.class
    );
    if (columnCount == null || columnCount != 18) {
      throw new CcdDataMigrationException(
          "CCD data migration progress table is missing or incomplete. "
              + "Run the decentralised-runtime Flyway migrations before starting the task."
      );
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
        statement.setString(2, String.join(",", options.caseTypeIds()));
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
        statement.setString(2, String.join(",", options.caseTypeIds()));
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
        Map.of("fdwSchema", FDW_SCHEMA),
        Integer.class
    );
    if (missingTables == null || missingTables != 0) {
      throw new CcdDataMigrationException(
          "FDW foreign tables are missing in schema " + FDW_SCHEMA
              + ". Run the FDW setup first: " + DOCS_URL
      );
    }
  }

  private void prepareTarget() {
    log.info("Preparing CCD migration target tables taskName={}", options.taskName());
    db.getJdbcTemplate().execute("""
        alter table ccd.case_event
        drop constraint if exists case_event_case_data_id_fkey
        """);
    db.getJdbcTemplate().execute("""
        drop index if exists ccd.idx_case_event_case_data_revision_unique
        """);
    db.getJdbcTemplate().execute("""
        alter table ccd.case_event
        disable trigger user
        """);
  }

  private void markTargetPrepared() {
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set target_prepared = true,
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of("taskName", options.taskName())
    );
  }

  private void markTargetRestored() {
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set target_prepared = false,
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of("taskName", options.taskName())
    );
  }

  private RunTotals processAvailableBatches() {
    var totals = new RunTotals();
    Progress progress = getOrCreateProgress();
    LocalDateTime stopAt = calculateStopAt();

    for (int i = 0; i < options.maxBatchesPerRun(); i++) {
      progress = prepareDeltaWindow(progress);
      List<CaseDataCursor> cases = findNextCases(progress);

      if (cases.isEmpty()) {
        String completedPhase = progress.phase();
        progress = completeCurrentWindow(progress);
        if (DELTA_PHASE.equals(completedPhase)) {
          totals = totals.markCaughtUp();
          break;
        }
        continue;
      }

      List<Long> caseDataIds = cases.stream().map(CaseDataCursor::id).toList();
      ensureTargetPrepared(progress);
      BatchResult batch = migrateBatch(caseDataIds);
      totals = totals.plus(batch);
      progress = recordBatch(cases, batch);
      log.info(
          "CCD data migration progress taskName={} phase={} batchCases={} batchEvents={} "
              + "lastCaseDataModified={} lastCaseDataId={} totalBatches={} totalCases={} totalEvents={}",
          options.taskName(),
          progress.phase(),
          batch.cases(),
          batch.events(),
          progress.lastCaseDataModified(),
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

  private void ensureTargetPrepared(Progress progress) {
    if (progress.targetPrepared()) {
      return;
    }

    var prepared = false;
    try {
      validateCanDisableTriggers();
      prepareTarget();
      prepared = true;
      markTargetPrepared();
    } catch (RuntimeException ex) {
      if (prepared) {
        restoreConstraintsAfterFailure();
      }
      throw ex;
    }
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
        insert into ccd.ccd_data_migration_progress (
          task_name,
          config_hash,
          target_schema,
          fdw_schema,
          case_type_ids,
          case_revision_offset,
          phase,
          window_end
        ) values (
          :taskName,
          :configHash,
          :targetSchema,
          :fdwSchema,
          :caseTypeIds,
          :caseRevisionOffset,
          :phase,
          now() at time zone 'UTC'
        )
        on conflict (task_name) do nothing
        """,
        progressConfigParams().addValue("phase", INITIAL_PHASE)
    );
    return loadProgress();
  }

  private Progress loadProgress() {
    Progress progress = db.queryForObject(
        """
        select task_name,
               config_hash,
               target_schema,
               fdw_schema,
               case_type_ids,
               case_revision_offset,
               phase,
               window_start,
               window_end,
               last_case_data_modified,
               last_case_data_id,
               initial_complete,
               total_batches,
               total_cases,
               total_events,
               target_prepared
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
        .addValue("configHash", options.migrationConfigHash())
        .addValue("targetSchema", TARGET_SCHEMA)
        .addValue("fdwSchema", FDW_SCHEMA)
        .addValue("caseTypeIds", options.canonicalCaseTypeIds())
        .addValue("caseRevisionOffset", options.caseRevisionOffset());
  }

  private void validateProgressConfiguration(Progress progress) {
    if (!options.migrationConfigHash().equals(progress.configHash())) {
      throw new CcdDataMigrationException(
          "CCD data migration progress for taskName=" + options.taskName()
              + " was created with a different migration configuration. Existing configuration: "
              + progress.configSummary() + ". Current configuration: "
              + options.migrationConfigSummary()
              + ". Use a new taskName for a different migration, or reset ccd_data_migration_progress only "
              + "after confirming the existing migration state is no longer needed."
      );
    }
  }

  private Progress prepareDeltaWindow(Progress progress) {
    if (!DELTA_PHASE.equals(progress.phase()) || !progress.windowStart().equals(progress.windowEnd())) {
      return progress;
    }

    db.update(
        """
        update ccd.ccd_data_migration_progress
        set window_start = :windowStart,
            window_end = now() at time zone 'UTC',
            last_case_data_modified = null,
            last_case_data_id = 0,
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of(
            "taskName", options.taskName(),
            "windowStart", deltaWindowStart(progress.windowStart())
        )
    );
    return loadProgress();
  }

  private List<CaseDataCursor> findNextCases(Progress progress) {
    var params = new MapSqlParameterSource()
        .addValue("caseTypeIds", options.caseTypeIds())
        .addValue("lastCaseDataId", progress.lastCaseDataId())
        .addValue("lastCaseDataModified", progress.lastCaseDataModified(), Types.TIMESTAMP)
        .addValue("hasDeltaCursor", progress.lastCaseDataModified() != null)
        .addValue("windowStart", progress.windowStart())
        .addValue("windowEnd", progress.windowEnd())
        .addValue("batchSize", options.batchSize());

    if (DELTA_PHASE.equals(progress.phase())) {
      return findNextDeltaCases(params);
    }

    return db.query(
        """
        select id,
               coalesce(last_modified, created_date) as modified_at
        from fdw_stage.case_data
        where case_type_id in (:caseTypeIds)
          and id > :lastCaseDataId
          and coalesce(last_modified, created_date) < :windowEnd
        order by id
        limit :batchSize
        """,
        params,
        (rs, rowNum) -> mapCaseDataCursor(rs)
    );
  }

  private List<CaseDataCursor> findNextDeltaCases(MapSqlParameterSource params) {
    return db.query(
        """
        select id,
               modified_at
        from (
          select id,
                 coalesce(last_modified, created_date) as modified_at
          from fdw_stage.case_data
          where case_type_id in (:caseTypeIds)
            and coalesce(last_modified, created_date) >= :windowStart
            and coalesce(last_modified, created_date) < :windowEnd
        ) eligible
        where :hasDeltaCursor = false
           or (
             modified_at > :lastCaseDataModified
             or (
               modified_at = :lastCaseDataModified
               and id > :lastCaseDataId
             )
           )
        order by modified_at, id
        limit :batchSize
        """,
        params,
        (rs, rowNum) -> mapCaseDataCursor(rs)
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
          update ccd.ccd_data_migration_progress
          set phase = :phase,
              initial_complete = true,
              window_start = :windowStart,
              window_end = now() at time zone 'UTC',
              last_case_data_modified = null,
              last_case_data_id = 0,
              updated_at = now() at time zone 'UTC'
          where task_name = :taskName
          """,
          Map.of(
              "taskName", options.taskName(),
              "phase", DELTA_PHASE,
              "windowStart", deltaWindowStart(progress.windowEnd())
          )
      );
    } else {
      log.info(
          "CCD data migration caught up taskName={} deltaWindowEnd={}",
          options.taskName(),
          progress.windowEnd()
      );
      db.update(
          """
          update ccd.ccd_data_migration_progress
          set window_start = window_end,
              last_case_data_modified = null,
              last_case_data_id = 0,
              updated_at = now() at time zone 'UTC'
          where task_name = :taskName
          """,
          Map.of("taskName", options.taskName())
      );
    }
    return loadProgress();
  }

  private LocalDateTime deltaWindowStart(LocalDateTime windowStart) {
    return windowStart.minus(options.deltaOverlap());
  }

  private BatchResult migrateBatch(List<Long> caseDataIds) {
    return transaction.execute(status -> {
      db.getJdbcTemplate().execute("set local session_replication_role = replica");
      int cases = loadCaseData(caseDataIds);
      validateNoConflictingCaseEvents(caseDataIds);
      int events = loadCaseEvents(caseDataIds);
      recalculateRevisions(caseDataIds);
      return new BatchResult(cases, events);
    });
  }

  private int loadCaseData(List<Long> caseDataIds) {
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
        from fdw_stage.case_data
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
          ccd.case_data.version,
          ccd.case_data.last_modified,
          ccd.case_data.case_revision,
          ccd.case_data.state,
          ccd.case_data.data,
          ccd.case_data.supplementary_data
        ) is distinct from (
          excluded.version,
          excluded.last_modified,
          excluded.case_revision,
          excluded.state,
          excluded.data,
          excluded.supplementary_data
        )
        """,
        batchParams(caseDataIds)
    );
  }

  private void validateNoConflictingCaseEvents(List<Long> caseDataIds) {
    List<Long> conflictingEventIds = db.query(
        """
        select target.id
        from ccd.case_event target
        join fdw_stage.case_event source on source.id = target.id
        where source.case_data_id in (:caseDataIds)
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
        batchParams(caseDataIds),
        (rs, rowNum) -> rs.getLong("id")
    );

    if (!conflictingEventIds.isEmpty()) {
      throw new CcdDataMigrationException(
          "Found conflicting case_event rows already in the target for taskName=" + options.taskName()
              + " eventIds=" + conflictingEventIds
      );
    }
  }

  private int loadCaseEvents(List<Long> caseDataIds) {
    return db.update(
        """
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
          gen_random_uuid(),
          0,
          0
        from fdw_stage.case_event ce
        where ce.case_data_id in (:caseDataIds)
        on conflict (id) do nothing
        """,
        batchParams(caseDataIds)
    );
  }

  private void recalculateRevisions(List<Long> caseDataIds) {
    db.getJdbcTemplate().execute("""
        create index if not exists idx_tmp_case_event_case_data_id_id
        on ccd.case_event (case_data_id, id)
        """);

    db.update(
        """
        update ccd.case_event target
        set version = source.revision::int,
            case_revision = source.revision::bigint
        from (
          select id,
                 row_number() over (
                   partition by case_data_id
                   order by id
                 ) as revision
          from ccd.case_event
          where case_data_id in (:caseDataIds)
        ) source
        where target.id = source.id
        """,
        batchParams(caseDataIds)
    );

    db.update(
        """
        update ccd.case_data cd
        set case_revision = counts.event_count + :caseRevisionOffset
        from (
          select cd2.id,
                 count(ce.id)::bigint as event_count
          from ccd.case_data cd2
          left join ccd.case_event ce on ce.case_data_id = cd2.id
          where cd2.id in (:caseDataIds)
          group by cd2.id
        ) counts
        where cd.id = counts.id
        """,
        batchParams(caseDataIds).addValue("caseRevisionOffset", options.caseRevisionOffset())
    );
  }

  private Progress recordBatch(List<CaseDataCursor> cases, BatchResult batch) {
    CaseDataCursor lastCase = cases.get(cases.size() - 1);
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set last_case_data_modified = :lastCaseDataModified,
            last_case_data_id = :lastCaseDataId,
            total_batches = total_batches + 1,
            total_cases = total_cases + :cases,
            total_events = total_events + :events,
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of(
            "taskName", options.taskName(),
            "lastCaseDataModified", lastCase.modifiedAt(),
            "lastCaseDataId", lastCase.id(),
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

  private void restoreConstraints() {
    log.info("Restoring CCD migration target constraints taskName={}", options.taskName());
    db.getJdbcTemplate().execute("""
        create unique index if not exists idx_case_event_case_data_revision_unique
        on ccd.case_event (case_data_id, case_revision)
        """);
    db.getJdbcTemplate().execute("""
        do $$
        begin
          if not exists (
            select 1
            from pg_constraint
            where conname = 'case_event_case_data_id_fkey'
              and conrelid = 'ccd.case_event'::regclass
          ) then
            alter table ccd.case_event
            add constraint case_event_case_data_id_fkey
            foreign key (case_data_id)
            references ccd.case_data(id)
            on delete cascade;
          end if;
        end $$;
        """);
    db.getJdbcTemplate().execute("""
        alter table ccd.case_event
        enable trigger user
        """);
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
          'ccd.case_event_id_seq'::regclass,
          (select coalesce(max(id), 1) from ccd.case_event),
          true
        )
        """);
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

  private void validateCaseRevisionAlignment() {
    Long mismatchCount = db.queryForObject(
        """
        with case_revision_check as (
          select cd.id,
                 cd.case_revision,
                 count(ce.id)::bigint as event_count,
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
           or event_count is distinct from max_event_revision
        """,
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
        rs.getString("config_hash"),
        rs.getString("target_schema"),
        rs.getString("fdw_schema"),
        rs.getString("case_type_ids"),
        rs.getLong("case_revision_offset"),
        rs.getString("phase"),
        rs.getObject("window_start", LocalDateTime.class),
        rs.getObject("window_end", LocalDateTime.class),
        rs.getObject("last_case_data_modified", LocalDateTime.class),
        rs.getLong("last_case_data_id"),
        rs.getBoolean("initial_complete"),
        rs.getLong("total_batches"),
        rs.getLong("total_cases"),
        rs.getLong("total_events"),
        rs.getBoolean("target_prepared")
    );
  }

  private CaseDataCursor mapCaseDataCursor(ResultSet rs) throws SQLException {
    return new CaseDataCursor(
        rs.getLong("id"),
        rs.getObject("modified_at", LocalDateTime.class)
    );
  }

  private record Progress(
      String configHash,
      String targetSchema,
      String fdwSchema,
      String caseTypeIds,
      long caseRevisionOffset,
      String phase,
      LocalDateTime windowStart,
      LocalDateTime windowEnd,
      LocalDateTime lastCaseDataModified,
      long lastCaseDataId,
      boolean initialComplete,
      long totalBatches,
      long totalCases,
      long totalEvents,
      boolean targetPrepared
  ) {
    private String configSummary() {
      return "targetSchema=" + targetSchema
          + ", fdwSchema=" + fdwSchema
          + ", caseTypeIds=" + caseTypeIds
          + ", caseRevisionOffset=" + caseRevisionOffset;
    }
  }

  private record CaseDataCursor(long id, LocalDateTime modifiedAt) {
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
