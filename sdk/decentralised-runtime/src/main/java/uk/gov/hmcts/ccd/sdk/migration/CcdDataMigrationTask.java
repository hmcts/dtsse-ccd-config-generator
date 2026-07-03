package uk.gov.hmcts.ccd.sdk.migration;

import static uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationMode.CUTOVER;
import static uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationMode.PRELOAD_EVENTS;
import static uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationMode.VALIDATE_ONLY;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
            + "maxBatchesPerRun={} maxRunTime={}",
        options.taskName(),
        options.mode(),
        options.caseTypeIds(),
        options.eventBatchSize(),
        options.maxBatchesPerRun(),
        options.maxRunTime()
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

    prepareElasticsearchQueueForMigration();
    long targetEventHwm = sourceEventHighWaterMark();
    CopyTotals totals = copyEventBatches(targetEventHwm);
    long localEventHwm = localEventHighWaterMark();
    boolean caughtUp = localEventHwm >= targetEventHwm;
    log.info(
        "Completed CCD data migration preload taskName={} targetEventHwm={} batches={} cases={} events={} "
            + "caughtUp={} stoppedByTimeLimit={}",
        options.taskName(),
        targetEventHwm,
        totals.batches(),
        0,
        totals.events(),
        caughtUp,
        totals.stoppedByTimeLimit()
    );
    return new CcdDataMigrationRunResult(
        true,
        totals.batches(),
        0,
        totals.events(),
        caughtUp,
        totals.stoppedByTimeLimit()
    );
  }

  private CcdDataMigrationRunResult cutover() {
    Progress progress = getOrCreateProgress();
    if (STATUS_COMPLETE.equals(progress.status())) {
      enableElasticsearchQueueTrigger();
      validateFinal(progress.requiredCutoverEventHwm());
      return new CcdDataMigrationRunResult(true, 0, 0, 0, true, false);
    }

    prepareElasticsearchQueueForMigration();
    long cutoverEventHwm = progress.cutoverEventHwm() == null
        ? captureCutoverEventHighWaterMark()
        : progress.cutoverEventHwm();
    CopyTotals totals = copyEventBatches(cutoverEventHwm);
    long localEventHwm = localEventHighWaterMark();
    if (totals.stoppedByTimeLimit() || localEventHwm < cutoverEventHwm) {
      log.info(
          "CCD data migration cutover paused before final refresh taskName={} cutoverEventHwm={} localEventHwm={} "
              + "batches={} events={} stoppedByTimeLimit={}",
          options.taskName(),
          cutoverEventHwm,
          localEventHwm,
          totals.batches(),
          totals.events(),
          totals.stoppedByTimeLimit()
      );
      return new CcdDataMigrationRunResult(
          true,
          totals.batches(),
          0,
          totals.events(),
          false,
          totals.stoppedByTimeLimit()
      );
    }
    final int refreshedCases = refreshCutoverCaseData();
    resetSequences();
    validateFinal(cutoverEventHwm);
    enableElasticsearchQueueTrigger();
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

  private CopyTotals copyEventBatches(long targetEventHwm) {
    var totals = new CopyTotals();
    LocalDateTime stopAt = calculateStopAt();

    for (int i = 0; i < options.maxBatchesPerRun(); i++) {
      long localEventHwm = localEventHighWaterMark();
      if (localEventHwm >= targetEventHwm) {
        totals = totals.markCaughtUp();
        break;
      }

      Long batchEndEventHwm = nextSourceEventBatchEndAfter(localEventHwm, targetEventHwm);
      if (batchEndEventHwm == null) {
        totals = totals.markCaughtUp();
        break;
      }

      long batchStartEventId = localEventHwm + 1L;
      int events = transaction.execute(status -> copyNextEventBatch(batchStartEventId, batchEndEventHwm));
      if (events == 0) {
        totals = totals.markCaughtUp();
        break;
      }
      totals = totals.plus(events);
      log.info(
          "CCD data migration progress taskName={} mode={} localEventHwm={} targetEventHwm={} "
              + "batchStartEventId={} batchEndEventHwm={} batchEvents={} totalBatches={} totalEvents={}",
          options.taskName(),
          options.mode(),
          localEventHwm,
          targetEventHwm,
          batchStartEventId,
          batchEndEventHwm,
          events,
          totals.batches(),
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

  private int copyNextEventBatch(long batchStartEventId, long batchEndEventHwm) {
    return db.update(
        """
        with source_cases as materialized (
          select distinct on (cd.id) cd.*
          from fdw_stage.case_data cd
          join fdw_stage.case_event ce on ce.case_data_id = cd.id
          where ce.case_type_id in (:caseTypeIds)
            and ce.id >= :batchStartEventId
            and ce.id <= :batchEndEventHwm
          order by cd.id
        ),
        inserted_cases as (
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
          on conflict do nothing
          returning id
        ),
        events_to_insert as materialized (
          select ce.*
          from fdw_stage.case_event ce
          where ce.case_type_id in (:caseTypeIds)
            and ce.id >= :batchStartEventId
            and ce.id <= :batchEndEventHwm
        ),
        affected_cases as (
          select distinct case_data_id
          from events_to_insert
        ),
        local_revisions as (
          select ce.case_data_id, max(ce.case_revision)::bigint as case_revision
          from ccd.case_event ce
          join affected_cases ac on ac.case_data_id = ce.case_data_id
          group by ce.case_data_id
        ),
        numbered_events as (
          select ce.*,
                 row_number() over (
                   partition by ce.case_data_id
                   order by ce.id
                 ) as calculated_revision
          from events_to_insert ce
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
          numbered_events.id,
          numbered_events.created_date,
          numbered_events.security_classification,
          numbered_events.case_data_id,
          numbered_events.case_type_version,
          numbered_events.event_id,
          numbered_events.summary,
          numbered_events.description,
          numbered_events.user_id,
          numbered_events.case_type_id,
          numbered_events.state_id,
          numbered_events.data,
          numbered_events.user_first_name,
          numbered_events.user_last_name,
          numbered_events.event_name,
          numbered_events.state_name,
          numbered_events.proxied_by,
          numbered_events.proxied_by_first_name,
          numbered_events.proxied_by_last_name,
          gen_random_uuid(),
          (coalesce(local_revisions.case_revision, 0) + calculated_revision)::int,
          coalesce(local_revisions.case_revision, 0) + calculated_revision
        from numbered_events
        left join local_revisions on local_revisions.case_data_id = numbered_events.case_data_id
        """,
        eventBatchParams(batchStartEventId, batchEndEventHwm)
    );
  }

  private int refreshCutoverCaseData() {
    return transaction.execute(status -> {
      disableCaseDataRevisionTrigger();
      try {
        int refreshedCases = syncCaseDataForCutover();
        updateCaseRevisionsForCutover();
        return refreshedCases;
      } finally {
        enableCaseDataRevisionTrigger();
      }
    });
  }

  private int syncCaseDataForCutover() {
    return db.update(
        """
        update ccd.case_data target
        set reference = source.reference,
            version = source.version,
            created_date = source.created_date,
            security_classification = source.security_classification,
            last_state_modified_date = source.last_state_modified_date,
            resolved_ttl = source.resolved_ttl,
            last_modified = source.last_modified,
            jurisdiction = source.jurisdiction,
            case_type_id = source.case_type_id,
            state = source.state,
            data = source.data,
            supplementary_data = coalesce(source.supplementary_data, jsonb_build_object())
        from fdw_stage.case_data source
        where target.id = source.id
          and source.case_type_id in (:caseTypeIds)
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
        """,
        baseParams()
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
        update ccd.case_data target
        set case_revision = event_counts.max_revision + :caseRevisionOffset
        from event_counts
        where target.id = event_counts.case_data_id
          and target.case_type_id in (:caseTypeIds)
        """,
        baseParams().addValue("caseRevisionOffset", options.caseRevisionOffset())
    );
  }

  private long sourceEventHighWaterMark() {
    Long hwm = db.queryForObject(
        """
        select coalesce(max(ce.id), 0)
        from fdw_stage.case_event ce
        where ce.case_type_id in (:caseTypeIds)
        """,
        baseParams(),
        Long.class
    );
    return hwm == null ? 0 : hwm;
  }

  private long localEventHighWaterMark() {
    Long hwm = db.queryForObject(
        """
        select coalesce(max(ce.id), 0)
        from ccd.case_event ce
        where ce.case_type_id in (:caseTypeIds)
        """,
        baseParams(),
        Long.class
    );
    return hwm == null ? 0 : hwm;
  }

  private Long nextSourceEventBatchEndAfter(long localEventHwm, long targetEventHwm) {
    return db.queryForObject(
        """
        -- The + 0 prevents PostgreSQL's min/max optimisation from walking pk_case_event
        -- when the case_type_id predicate is sparse on the source CCD table.
        select coalesce(
          (array_agg(ce.id + 0 order by ce.id))[:eventBatchSize],
          max(ce.id + 0)
        )
        from fdw_stage.case_event ce
        where ce.case_type_id in (:caseTypeIds)
          and ce.id > :localEventHwm
          and ce.id <= :targetEventHwm
        """,
        baseParams()
            .addValue("localEventHwm", localEventHwm)
            .addValue("targetEventHwm", targetEventHwm)
            .addValue("eventBatchSize", options.eventBatchSize()),
        Long.class
    );
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
    log.info("CCD data migration final counts taskName={} caseData={}", options.taskName(), queryCounts("case_data"));
    log.info("CCD data migration final counts taskName={} caseEvent={}", options.taskName(), queryCounts("case_event"));
    long esQueueRows = countElasticsearchQueueRows();
    log.info("CCD data migration final counts taskName={} esQueue={}", options.taskName(), esQueueRows);
    if (esQueueRows > 0) {
      throw new CcdDataMigrationException(
          "CCD data migration left " + esQueueRows + " Elasticsearch queue rows"
      );
    }
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

  private void prepareElasticsearchQueueForMigration() {
    disableElasticsearchQueueTrigger();
    truncateElasticsearchQueue();
  }

  private void truncateElasticsearchQueue() {
    db.getJdbcTemplate().execute("truncate table ccd.es_queue");
  }

  private long countElasticsearchQueueRows() {
    Long rows = db.queryForObject(
        "select count(*) from ccd.es_queue",
        Map.of(),
        Long.class
    );
    return rows == null ? 0 : rows;
  }

  private void disableCaseDataRevisionTrigger() {
    db.getJdbcTemplate().execute("alter table ccd.case_data disable trigger trigger_increment_case_revision");
  }

  private void enableCaseDataRevisionTrigger() {
    db.getJdbcTemplate().execute("alter table ccd.case_data enable trigger trigger_increment_case_revision");
  }

  private void disableElasticsearchQueueTrigger() {
    db.getJdbcTemplate().execute("alter table ccd.case_data disable trigger trigger_enqueue_case_revision");
  }

  private void enableElasticsearchQueueTrigger() {
    db.getJdbcTemplate().execute("alter table ccd.case_data enable trigger trigger_enqueue_case_revision");
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
            'cutover_event_hwm',
            'created_at',
            'updated_at'
          )
        """,
        Map.of("schema", TARGET_SCHEMA),
        Integer.class
    );
    if (columnCount == null || columnCount != 6) {
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

  private MapSqlParameterSource eventBatchParams(long batchStartEventId, long batchEndEventHwm) {
    return baseParams()
        .addValue("batchStartEventId", batchStartEventId)
        .addValue("batchEndEventHwm", batchEndEventHwm);
  }

  private MapSqlParameterSource baseParams() {
    return new MapSqlParameterSource().addValue("caseTypeIds", options.caseTypeIds());
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

  private record Progress(String configHash, String status, Long cutoverEventHwm) {
    long requiredCutoverEventHwm() {
      if (cutoverEventHwm == null) {
        throw new CcdDataMigrationException("cutover_event_hwm is not set for completed migration");
      }
      return cutoverEventHwm;
    }
  }

  private record CopyTotals(long batches, long events, boolean caughtUp, boolean stoppedByTimeLimit) {
    private CopyTotals() {
      this(0, 0, false, false);
    }

    private CopyTotals plus(int copiedEvents) {
      return new CopyTotals(batches + 1, events + copiedEvents, caughtUp, stoppedByTimeLimit);
    }

    private CopyTotals markCaughtUp() {
      return new CopyTotals(batches, events, true, stoppedByTimeLimit);
    }

    private CopyTotals markStoppedByTimeLimit() {
      return new CopyTotals(batches, events, caughtUp, true);
    }
  }
}
