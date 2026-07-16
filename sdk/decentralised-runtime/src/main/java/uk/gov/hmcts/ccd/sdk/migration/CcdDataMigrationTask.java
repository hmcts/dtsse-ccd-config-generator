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
import java.util.function.Supplier;
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
  private static final int SOURCE_EVENT_SAFETY_SCAN_LIMIT = 100_000;

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
        "Starting CCD data migration task taskName={} mode={} caseTypeIds={} eventIdWindowSize={} "
            + "significantItemIdWindowSize={} maxBatchesPerRun={} maxRunTime={} sourceEventSafetyWindow={}",
        options.taskName(),
        options.mode(),
        options.caseTypeIds(),
        options.eventIdWindowSize(),
        options.significantItemIdWindowSize(),
        options.maxBatchesPerRun(),
        options.maxRunTime(),
        options.sourceEventSafetyWindow()
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
    if (STATUS_COMPLETE.equals(progress.status())) {
      reopenPreload();
    } else if (!STATUS_PRELOAD.equals(progress.status())) {
      throw new CcdDataMigrationException(
          "PRELOAD_EVENTS cannot run when migration status is " + progress.status()
              + " taskName=" + options.taskName()
      );
    }

    prepareElasticsearchQueueForMigration();
    long targetEventHwm = sourceEventHighWaterMark();
    validateSourceEventHighWaterMarkHasNotRegressed(targetEventHwm);
    LocalDateTime stopAt = calculateStopAt();
    BatchBudget batchBudget = new BatchBudget(options.maxBatchesPerRun());
    CopyTotals totals = copyEventBatches(targetEventHwm, stopAt, batchBudget);
    long sourceEventHwm = sourceEventProgressHighWaterMark();
    SignificantItemCopyTotals significantItemTotals = copySignificantItemBatches(sourceEventHwm, stopAt, batchBudget);
    ValidationTotals eventValidationTotals = validateEventBatches(sourceEventHwm, stopAt, batchBudget);
    ValidationTotals significantItemValidationTotals = validateSignificantItemBatches(stopAt, batchBudget);
    boolean caughtUp = sourceEventHwm >= targetEventHwm
        && significantItemTotals.caughtUp()
        && eventValidationTotals.caughtUp()
        && significantItemValidationTotals.caughtUp();
    log.info(
        "Completed CCD data migration preload taskName={} targetEventHwm={} batches={} cases={} events={} "
            + "significantItems={} significantItemBatches={} validatedEventBatches={} "
            + "validatedSignificantItemBatches={} caughtUp={} stoppedByTimeLimit={}",
        options.taskName(),
        targetEventHwm,
        batchBudget.consumed(),
        0,
        totals.events(),
        significantItemTotals.items(),
        significantItemTotals.batches(),
        eventValidationTotals.batches(),
        significantItemValidationTotals.batches(),
        caughtUp,
        totals.stoppedByTimeLimit()
            || significantItemTotals.stoppedByTimeLimit()
            || eventValidationTotals.stoppedByTimeLimit()
            || significantItemValidationTotals.stoppedByTimeLimit()
    );
    return new CcdDataMigrationRunResult(
        true,
        batchBudget.consumed(),
        0,
        totals.events(),
        caughtUp,
        totals.stoppedByTimeLimit()
            || significantItemTotals.stoppedByTimeLimit()
            || eventValidationTotals.stoppedByTimeLimit()
            || significantItemValidationTotals.stoppedByTimeLimit()
    );
  }

  private CcdDataMigrationRunResult cutover() {
    Progress progress = getOrCreateProgress();
    if (STATUS_COMPLETE.equals(progress.status())) {
      prepareCompleteProgressForNextCutover(progress.requiredCutoverEventHwm());
    }

    if (progress.cutoverEventHwm() == null || STATUS_COMPLETE.equals(progress.status())) {
      validateSourceEventHighWaterMarkHasNotRegressed(sourceEventHighWaterMark());
      if (sourceHasFreshEvents()) {
        log.info(
            "CCD data migration cutover paused before capturing high-water mark taskName={} because source events "
                + "inside safety window still exist",
            options.taskName()
        );
        return new CcdDataMigrationRunResult(true, 0, 0, 0, false, false);
      }
    }
    prepareElasticsearchQueueForMigration();
    long cutoverEventHwm = progress.cutoverEventHwm() == null || STATUS_COMPLETE.equals(progress.status())
        ? captureCutoverEventHighWaterMark()
        : progress.cutoverEventHwm();
    LocalDateTime stopAt = calculateStopAt();
    BatchBudget batchBudget = new BatchBudget(options.maxBatchesPerRun());
    CopyTotals totals = copyEventBatches(cutoverEventHwm, stopAt, batchBudget);
    long sourceEventHwm = sourceEventProgressHighWaterMark();
    if (totals.stoppedByTimeLimit() || sourceEventHwm < cutoverEventHwm) {
      log.info(
          "CCD data migration cutover paused before final refresh taskName={} cutoverEventHwm={} sourceEventHwm={} "
              + "batches={} events={} stoppedByTimeLimit={}",
          options.taskName(),
          cutoverEventHwm,
          sourceEventHwm,
          batchBudget.consumed(),
          totals.events(),
          totals.stoppedByTimeLimit()
      );
      return new CcdDataMigrationRunResult(
          true,
          batchBudget.consumed(),
          0,
          totals.events(),
          false,
          totals.stoppedByTimeLimit()
      );
    }
    SignificantItemCopyTotals significantItemTotals = copySignificantItemBatches(cutoverEventHwm, stopAt, batchBudget);
    if (!significantItemTotals.caughtUp()) {
      log.info(
          "CCD data migration cutover paused before final refresh taskName={} cutoverEventHwm={} "
              + "significantItemBatches={} significantItems={} stoppedByTimeLimit={}",
          options.taskName(),
          cutoverEventHwm,
          significantItemTotals.batches(),
          significantItemTotals.items(),
          significantItemTotals.stoppedByTimeLimit()
      );
      return new CcdDataMigrationRunResult(
          true,
          batchBudget.consumed(),
          0,
          totals.events(),
          false,
          significantItemTotals.stoppedByTimeLimit()
      );
    }
    ValidationTotals eventValidationTotals = validateEventBatches(cutoverEventHwm, stopAt, batchBudget);
    ValidationTotals significantItemValidationTotals = validateSignificantItemBatches(stopAt, batchBudget);
    if (!eventValidationTotals.caughtUp() || !significantItemValidationTotals.caughtUp()) {
      log.info(
          "CCD data migration cutover paused before final refresh taskName={} cutoverEventHwm={} "
              + "validatedEventBatches={} validatedSignificantItemBatches={} stoppedByTimeLimit={}",
          options.taskName(),
          cutoverEventHwm,
          eventValidationTotals.batches(),
          significantItemValidationTotals.batches(),
          eventValidationTotals.stoppedByTimeLimit() || significantItemValidationTotals.stoppedByTimeLimit()
      );
      return new CcdDataMigrationRunResult(
          true,
          batchBudget.consumed(),
          0,
          totals.events(),
          false,
          eventValidationTotals.stoppedByTimeLimit() || significantItemValidationTotals.stoppedByTimeLimit()
      );
    }
    if (releaseCutoverHighWaterMarkIfSourceAdvanced(cutoverEventHwm)) {
      return new CcdDataMigrationRunResult(true, batchBudget.consumed(), 0, totals.events(), false, false);
    }
    final int refreshedCases = refreshCutoverCaseData();
    resetSequences();
    completeCutover(cutoverEventHwm, stopAt);

    log.info(
        "Completed CCD data migration cutover taskName={} cutoverEventHwm={} batches={} refreshedCases={} events={} "
            + "significantItems={} significantItemBatches={} stoppedByTimeLimit={}",
        options.taskName(),
        cutoverEventHwm,
        batchBudget.consumed(),
        refreshedCases,
        totals.events(),
        significantItemTotals.items(),
        significantItemTotals.batches(),
        totals.stoppedByTimeLimit()
    );
    return new CcdDataMigrationRunResult(
        true,
        batchBudget.consumed(),
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
    validateSourceEventHighWaterMarkHasNotRegressed(validationEventHwm);
    BatchBudget batchBudget = new BatchBudget(options.maxBatchesPerRun());
    validateFinal(validationEventHwm, calculateStopAt(), batchBudget);
    return new CcdDataMigrationRunResult(true, batchBudget.consumed(), 0, 0, true, false);
  }

  private CopyTotals copyEventBatches(long targetEventHwm, LocalDateTime stopAt, BatchBudget batchBudget) {
    var totals = new CopyTotals();

    while (batchBudget.hasRemaining()) {
      long sourceEventHwm = effectiveSourceEventHighWaterMark(targetEventHwm);
      if (sourceEventHwm >= targetEventHwm) {
        totals = totals.markCaughtUp();
        break;
      }

      long batchEndEventHwm = nextFixedEventWindowEnd(sourceEventHwm, targetEventHwm);
      long batchStartEventId = sourceEventHwm + 1L;
      int events = transaction.execute(status -> {
        applyMigrationStatementTimeout();
        int copiedEvents = copyNextEventBatch(batchStartEventId, batchEndEventHwm);
        updateSourceEventProgressHighWaterMark(batchEndEventHwm);
        return copiedEvents;
      });
      batchBudget.consume();
      totals = totals.plus(events);
      log.info(
          "CCD data migration progress taskName={} mode={} sourceEventHwm={} targetEventHwm={} "
              + "batchStartEventId={} batchEndEventHwm={} batchEvents={} totalBatches={} totalEvents={}",
          options.taskName(),
          options.mode(),
          sourceEventHwm,
          targetEventHwm,
          batchStartEventId,
          batchEndEventHwm,
          events,
          totals.batches(),
          totals.events()
      );

      if (isTimeLimitReached(stopAt)) {
        log.info("Stopping CCD data migration taskName={} after event id window because time limit was reached",
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
        with events_to_insert as materialized (
          select ce.*
          from fdw_stage.case_event ce
          join fdw_stage.case_data cd on cd.id = ce.case_data_id
          where cd.jurisdiction = :sourceJurisdiction
            and cd.case_type_id in (:caseTypeIds)
            and ce.case_type_id in (:caseTypeIds)
            and ce.id >= :batchStartEventId
            and ce.id <= :batchEndEventHwm
        ),
        source_cases as materialized (
          select distinct on (cd.id) cd.*
          from fdw_stage.case_data cd
          join events_to_insert ce on ce.case_data_id = cd.id
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

  private SignificantItemCopyTotals copySignificantItemBatches(
      long cutoverEventHwm,
      LocalDateTime stopAt,
      BatchBudget batchBudget
  ) {
    long targetSignificantItemsHwm = sourceSignificantItemsHighWaterMark(cutoverEventHwm);
    validateSignificantItemsHighWaterMarkHasNotRegressed(targetSignificantItemsHwm);
    var totals = new SignificantItemCopyTotals();

    while (batchBudget.hasRemaining()) {
      long significantItemsHwm = significantItemsProgressHighWaterMark();
      if (significantItemsHwm >= targetSignificantItemsHwm) {
        return totals.markCaughtUp();
      }
      if (isTimeLimitReached(stopAt)) {
        return totals.markStoppedByTimeLimit();
      }

      long batchEndItemHwm = nextFixedSignificantItemWindowEnd(significantItemsHwm, targetSignificantItemsHwm);
      int copiedItems = transaction.execute(status -> {
        applyMigrationStatementTimeout();
        int copied = copySignificantItemBatch(cutoverEventHwm, significantItemsHwm, batchEndItemHwm);
        updateSignificantItemsProgressHighWaterMark(batchEndItemHwm);
        return copied;
      });
      batchBudget.consume();
      totals = totals.plus(copiedItems);
      log.info(
          "CCD data migration significant item progress taskName={} cutoverEventHwm={} "
              + "batchStartItemId={} batchEndItemHwm={} batchItems={} totalBatches={} totalItems={}",
          options.taskName(),
          cutoverEventHwm,
          significantItemsHwm + 1L,
          batchEndItemHwm,
          copiedItems,
          totals.batches(),
          totals.items()
      );
      if (isTimeLimitReached(stopAt)) {
        return totals.markStoppedByTimeLimit();
      }
    }
    if (significantItemsProgressHighWaterMark() >= targetSignificantItemsHwm) {
      return totals.markCaughtUp();
    }
    return totals;
  }

  private ValidationTotals validateEventBatches(
      long targetEventHwm,
      LocalDateTime stopAt,
      BatchBudget batchBudget
  ) {
    var totals = new ValidationTotals();
    while (batchBudget.hasRemaining()) {
      long validatedEventHwm = validatedEventHighWaterMark();
      if (validatedEventHwm >= targetEventHwm) {
        return totals.markCaughtUp();
      }
      if (isTimeLimitReached(stopAt)) {
        return totals.markStoppedByTimeLimit();
      }

      long batchEndEventHwm = nextLocalEventValidationHighWaterMark(validatedEventHwm, targetEventHwm);
      if (batchEndEventHwm <= validatedEventHwm) {
        updateValidatedEventHighWaterMark(targetEventHwm);
        return totals.markCaughtUp();
      }
      validateEventCounts(validatedEventHwm, batchEndEventHwm);
      updateValidatedEventHighWaterMark(batchEndEventHwm);
      batchBudget.consume();
      totals = totals.plus();
      log.info(
          "CCD data migration event validation progress taskName={} batchStartEventId={} batchEndEventHwm={} "
              + "totalBatches={}",
          options.taskName(),
          validatedEventHwm + 1L,
          batchEndEventHwm,
          totals.batches()
      );
    }
    return validatedEventHighWaterMark() >= targetEventHwm ? totals.markCaughtUp() : totals;
  }

  private long nextLocalEventValidationHighWaterMark(long validatedEventHwm, long targetEventHwm) {
    Long hwm = db.queryForObject(
        """
        select coalesce(max(id), :validatedEventHwm)
        from (
          select ce.id
          from ccd.case_event ce
          join ccd.case_data cd on cd.id = ce.case_data_id
          where cd.jurisdiction = :sourceJurisdiction
            and cd.case_type_id in (:caseTypeIds)
            and ce.case_type_id in (:caseTypeIds)
            and ce.id > :validatedEventHwm
            and ce.id <= :targetEventHwm
          order by ce.id
          limit :validationBatchSize
        ) local_events
        """,
        baseParams()
            .addValue("validatedEventHwm", validatedEventHwm)
            .addValue("targetEventHwm", targetEventHwm)
            .addValue("validationBatchSize", options.eventIdWindowSize()),
        Long.class
    );
    return hwm == null ? validatedEventHwm : hwm;
  }

  private void validateEventCounts(long fromExclusiveEventHwm, long toInclusiveEventHwm) {
    MapSqlParameterSource params = baseParams()
        .addValue("fromExclusiveEventHwm", fromExclusiveEventHwm)
        .addValue("toInclusiveEventHwm", toInclusiveEventHwm);
    Long sourceCount = withMigrationStatementTimeout(() -> db.queryForObject(
        """
        select count(*)
        from fdw_stage.case_event ce
        join fdw_stage.case_data cd on cd.id = ce.case_data_id
        where cd.jurisdiction = :sourceJurisdiction
          and cd.case_type_id in (:caseTypeIds)
          and ce.case_type_id in (:caseTypeIds)
          and ce.id > :fromExclusiveEventHwm
          and ce.id <= :toInclusiveEventHwm
        """,
        params,
        Long.class
    ));
    Long targetCount = db.queryForObject(
        """
        select count(*)
        from ccd.case_event ce
        join ccd.case_data cd on cd.id = ce.case_data_id
        where cd.jurisdiction = :sourceJurisdiction
          and cd.case_type_id in (:caseTypeIds)
          and ce.case_type_id in (:caseTypeIds)
          and ce.id > :fromExclusiveEventHwm
          and ce.id <= :toInclusiveEventHwm
        """,
        params,
        Long.class
    );

    long sourceRows = sourceCount == null ? 0 : sourceCount;
    long targetRows = targetCount == null ? 0 : targetCount;
    if (sourceRows != targetRows) {
      throw new CcdDataMigrationException(
          "CCD data migration event count mismatch source=" + sourceRows
              + " target=" + targetRows
              + " fromExclusiveEventHwm=" + fromExclusiveEventHwm
              + " toInclusiveEventHwm=" + toInclusiveEventHwm
      );
    }
  }

  private ValidationTotals validateSignificantItemBatches(LocalDateTime stopAt, BatchBudget batchBudget) {
    long targetSignificantItemsHwm = significantItemsProgressHighWaterMark();
    var totals = new ValidationTotals();
    while (batchBudget.hasRemaining()) {
      long validatedSignificantItemsHwm = validatedSignificantItemsHighWaterMark();
      if (validatedSignificantItemsHwm >= targetSignificantItemsHwm) {
        return totals.markCaughtUp();
      }
      if (isTimeLimitReached(stopAt)) {
        return totals.markStoppedByTimeLimit();
      }

      long batchEndItemHwm = nextLocalSignificantItemValidationHighWaterMark(
          validatedSignificantItemsHwm,
          targetSignificantItemsHwm,
          validatedEventHighWaterMark()
      );
      if (batchEndItemHwm <= validatedSignificantItemsHwm) {
        updateValidatedSignificantItemsHighWaterMark(targetSignificantItemsHwm);
        return totals.markCaughtUp();
      }
      validateSignificantItemCounts(validatedSignificantItemsHwm, batchEndItemHwm, validatedEventHighWaterMark());
      updateValidatedSignificantItemsHighWaterMark(batchEndItemHwm);
      batchBudget.consume();
      totals = totals.plus();
      log.info(
          "CCD data migration significant item validation progress taskName={} batchStartItemId={} "
              + "batchEndItemHwm={} totalBatches={}",
          options.taskName(),
          validatedSignificantItemsHwm + 1L,
          batchEndItemHwm,
          totals.batches()
      );
    }
    return validatedSignificantItemsHighWaterMark() >= targetSignificantItemsHwm ? totals.markCaughtUp() : totals;
  }

  private long nextLocalSignificantItemValidationHighWaterMark(
      long validatedSignificantItemsHwm,
      long targetSignificantItemsHwm,
      long eventHwm
  ) {
    Long hwm = db.queryForObject(
        """
        select coalesce(max(id), :validatedSignificantItemsHwm)
        from (
          select item.id
          from ccd.case_event_significant_items item
          join ccd.case_event ce on ce.id = item.case_event_id
          join ccd.case_data cd on cd.id = ce.case_data_id
          where cd.jurisdiction = :sourceJurisdiction
            and cd.case_type_id in (:caseTypeIds)
            and ce.case_type_id in (:caseTypeIds)
            and ce.id <= :eventHwm
            and item.id > :validatedSignificantItemsHwm
            and item.id <= :targetSignificantItemsHwm
          order by item.id
          limit :validationBatchSize
        ) local_items
        """,
        baseParams()
            .addValue("validatedSignificantItemsHwm", validatedSignificantItemsHwm)
            .addValue("targetSignificantItemsHwm", targetSignificantItemsHwm)
            .addValue("eventHwm", eventHwm)
            .addValue("validationBatchSize", options.significantItemIdWindowSize()),
        Long.class
    );
    return hwm == null ? validatedSignificantItemsHwm : hwm;
  }

  private void validateSignificantItemCounts(
      long fromExclusiveSignificantItemsHwm,
      long toInclusiveSignificantItemsHwm,
      long eventHwm
  ) {
    MapSqlParameterSource params = baseParams()
        .addValue("fromExclusiveSignificantItemsHwm", fromExclusiveSignificantItemsHwm)
        .addValue("toInclusiveSignificantItemsHwm", toInclusiveSignificantItemsHwm)
        .addValue("eventHwm", eventHwm);
    Long sourceCount = withMigrationStatementTimeout(() -> db.queryForObject(
        """
        select count(*)
        from fdw_stage.case_event_significant_items item
        join fdw_stage.case_event ce on ce.id = item.case_event_id
        join fdw_stage.case_data cd on cd.id = ce.case_data_id
        where cd.jurisdiction = :sourceJurisdiction
          and cd.case_type_id in (:caseTypeIds)
          and ce.case_type_id in (:caseTypeIds)
          and ce.id <= :eventHwm
          and item.id > :fromExclusiveSignificantItemsHwm
          and item.id <= :toInclusiveSignificantItemsHwm
        """,
        params,
        Long.class
    ));
    Long targetCount = db.queryForObject(
        """
        select count(*)
        from ccd.case_event_significant_items item
        join ccd.case_event ce on ce.id = item.case_event_id
        join ccd.case_data cd on cd.id = ce.case_data_id
        where cd.jurisdiction = :sourceJurisdiction
          and cd.case_type_id in (:caseTypeIds)
          and ce.case_type_id in (:caseTypeIds)
          and ce.id <= :eventHwm
          and item.id > :fromExclusiveSignificantItemsHwm
          and item.id <= :toInclusiveSignificantItemsHwm
        """,
        params,
        Long.class
    );

    long sourceRows = sourceCount == null ? 0 : sourceCount;
    long targetRows = targetCount == null ? 0 : targetCount;
    if (sourceRows != targetRows) {
      throw new CcdDataMigrationException(
          "CCD data migration significant item count mismatch source=" + sourceRows
              + " target=" + targetRows
              + " fromExclusiveSignificantItemsHwm=" + fromExclusiveSignificantItemsHwm
              + " toInclusiveSignificantItemsHwm=" + toInclusiveSignificantItemsHwm
              + " eventHwm=" + eventHwm
      );
    }
  }

  private int copySignificantItemBatch(
      long cutoverEventHwm,
      long significantItemsHwm,
      long targetSignificantItemsHwm
  ) {
    return db.update(
        """
        insert into ccd.case_event_significant_items (
          id,
          description,
          "type",
          url,
          case_event_id
        )
        select
          item.id,
          item.description,
          item."type"::ccd.significant_item_type,
          item.url,
          item.case_event_id
        from fdw_stage.case_event_significant_items item
        join fdw_stage.case_event ce on ce.id = item.case_event_id
        join fdw_stage.case_data cd on cd.id = ce.case_data_id
        join ccd.case_event target_event on target_event.id = item.case_event_id
        where cd.jurisdiction = :sourceJurisdiction
          and cd.case_type_id in (:caseTypeIds)
          and ce.case_type_id in (:caseTypeIds)
          and ce.id <= :cutoverEventHwm
          and item.id > :significantItemsHwm
          and item.id <= :targetSignificantItemsHwm
        on conflict (id) do nothing
        """,
        baseParams().addValue("cutoverEventHwm", cutoverEventHwm)
            .addValue("significantItemsHwm", significantItemsHwm)
            .addValue("targetSignificantItemsHwm", targetSignificantItemsHwm)
    );
  }

  private int refreshCutoverCaseData() {
    return transaction.execute(status -> {
      applyMigrationStatementTimeout();
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
          and source.jurisdiction = :sourceJurisdiction
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
          select ce.case_data_id, max(ce.case_revision)::bigint as max_revision
          from ccd.case_event ce
          join ccd.case_data cd on cd.id = ce.case_data_id
          where cd.jurisdiction = :sourceJurisdiction
            and cd.case_type_id in (:caseTypeIds)
            and ce.case_type_id in (:caseTypeIds)
          group by ce.case_data_id
        )
        update ccd.case_data target
        set case_revision = event_counts.max_revision + :caseRevisionOffset
        from event_counts
        where target.id = event_counts.case_data_id
          and target.jurisdiction = :sourceJurisdiction
          and target.case_type_id in (:caseTypeIds)
        """,
        baseParams().addValue("caseRevisionOffset", options.caseRevisionOffset())
    );
  }

  private long sourceEventHighWaterMark() {
    LocalDateTime sourceEventCreatedBefore = sourceEventCreatedBefore();
    return withMigrationStatementTimeout(() -> {
      Long hwm = db.queryForObject(
          """
          with recent_events as materialized (
            select ce.id, ce.created_date
            from fdw_stage.case_event ce
            order by ce.id desc
            limit :sourceEventSafetyScanLimit
          ),
          recent_stats as (
            select max(id) as max_event_hwm,
                   min(id) as min_scanned_event_hwm,
                   min(id) filter (where created_date >= :sourceEventCreatedBefore) as first_fresh_event_hwm,
                   bool_and(created_date >= :sourceEventCreatedBefore) as all_scanned_events_are_fresh
            from recent_events
          )
          select coalesce(
                   case
                     when first_fresh_event_hwm is null then max_event_hwm
                     when all_scanned_events_are_fresh then :progressEventHwm
                     else first_fresh_event_hwm - 1
                   end,
                   0
                 )
          from recent_stats
          """,
          baseParams()
              .addValue("sourceEventCreatedBefore", sourceEventCreatedBefore)
              .addValue("sourceEventSafetyScanLimit", SOURCE_EVENT_SAFETY_SCAN_LIMIT)
              .addValue("progressEventHwm", sourceEventProgressHighWaterMark()),
          Long.class
      );
      return hwm == null ? 0 : hwm;
    });
  }

  private boolean sourceHasFreshEvents() {
    LocalDateTime sourceEventCreatedBefore = sourceEventCreatedBefore();
    Boolean exists = withMigrationStatementTimeout(() -> db.queryForObject(
        """
        select exists (
          select 1
          from (
            select ce.created_date
            from fdw_stage.case_event ce
            order by ce.id desc
            limit :sourceEventSafetyScanLimit
          ) recent_events
          where created_date >= :sourceEventCreatedBefore
        )
        """,
        baseParams()
            .addValue("sourceEventCreatedBefore", sourceEventCreatedBefore)
            .addValue("sourceEventSafetyScanLimit", SOURCE_EVENT_SAFETY_SCAN_LIMIT),
        Boolean.class
    ));
    return Boolean.TRUE.equals(exists);
  }

  private boolean releaseCutoverHighWaterMarkIfSourceAdvanced(long cutoverEventHwm) {
    long currentSourceEventHwm = sourceEventHighWaterMark();
    boolean sourceHasFreshEvents = sourceHasFreshEvents();
    if (currentSourceEventHwm <= cutoverEventHwm && !sourceHasFreshEvents) {
      return false;
    }

    log.info(
        "CCD data migration cutover high-water mark released taskName={} cutoverEventHwm={} "
            + "currentSourceEventHwm={} sourceHasFreshEvents={}",
        options.taskName(),
        cutoverEventHwm,
        currentSourceEventHwm,
        sourceHasFreshEvents
    );
    releaseCutoverHighWaterMark(cutoverEventHwm);
    return true;
  }

  private void releaseCutoverHighWaterMark(long cutoverEventHwm) {
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set status = :status,
            cutover_event_hwm = null,
            source_event_hwm = greatest(source_event_hwm, :cutoverEventHwm),
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of(
            "taskName", options.taskName(),
            "status", STATUS_PRELOAD,
            "cutoverEventHwm", cutoverEventHwm
        )
    );
  }

  private void validateSourceEventHighWaterMarkHasNotRegressed(long currentSourceEventHwm) {
    long progressEventHwm = sourceEventProgressHighWaterMark();
    if (progressEventHwm > currentSourceEventHwm) {
      throw new CcdDataMigrationException(
          "CCD data migration source event high-water mark regressed taskName=" + options.taskName()
              + " sourceEventHwm=" + progressEventHwm
              + " currentSourceEventHwm=" + currentSourceEventHwm
              + ". A lower source event may have become visible after progress advanced; investigate before retrying."
      );
    }
  }

  private long localEventHighWaterMark() {
    return withMigrationStatementTimeout(() -> {
      Long hwm = db.queryForObject(
          """
          select coalesce((
            select ce.id
            from ccd.case_event ce
            join ccd.case_data cd on cd.id = ce.case_data_id
            where cd.jurisdiction = :sourceJurisdiction
              and cd.case_type_id in (:caseTypeIds)
              and ce.case_type_id in (:caseTypeIds)
            order by ce.id desc
            limit 1
          ), 0)
          """,
          baseParams(),
          Long.class
      );
      return hwm == null ? 0 : hwm;
    });
  }

  private long sourceEventProgressHighWaterMark() {
    Long hwm = db.queryForObject(
        """
        select source_event_hwm
        from ccd.ccd_data_migration_progress
        where task_name = :taskName
        """,
        Map.of("taskName", options.taskName()),
        Long.class
    );
    return hwm == null ? 0 : hwm;
  }

  private void updateSourceEventProgressHighWaterMark(long sourceEventHwm) {
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set source_event_hwm = :sourceEventHwm,
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of(
            "taskName", options.taskName(),
            "sourceEventHwm", sourceEventHwm
        )
    );
  }

  private long significantItemsProgressHighWaterMark() {
    Long hwm = db.queryForObject(
        """
        select significant_items_hwm
        from ccd.ccd_data_migration_progress
        where task_name = :taskName
        """,
        Map.of("taskName", options.taskName()),
        Long.class
    );
    return hwm == null ? 0 : hwm;
  }

  private long validatedEventHighWaterMark() {
    Long hwm = db.queryForObject(
        """
        select validated_event_hwm
        from ccd.ccd_data_migration_progress
        where task_name = :taskName
        """,
        Map.of("taskName", options.taskName()),
        Long.class
    );
    return hwm == null ? 0 : hwm;
  }

  private void updateValidatedEventHighWaterMark(long validatedEventHwm) {
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set validated_event_hwm = greatest(validated_event_hwm, :validatedEventHwm),
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of(
            "taskName", options.taskName(),
            "validatedEventHwm", validatedEventHwm
        )
    );
  }

  private long validatedSignificantItemsHighWaterMark() {
    Long hwm = db.queryForObject(
        """
        select validated_significant_items_hwm
        from ccd.ccd_data_migration_progress
        where task_name = :taskName
        """,
        Map.of("taskName", options.taskName()),
        Long.class
    );
    return hwm == null ? 0 : hwm;
  }

  private void updateValidatedSignificantItemsHighWaterMark(long validatedSignificantItemsHwm) {
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set validated_significant_items_hwm = greatest(
              validated_significant_items_hwm,
              :validatedSignificantItemsHwm
            ),
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of(
            "taskName", options.taskName(),
            "validatedSignificantItemsHwm", validatedSignificantItemsHwm
        )
    );
  }

  private long sourceSignificantItemsHighWaterMark(long cutoverEventHwm) {
    Long hwm = withMigrationStatementTimeout(() -> db.queryForObject(
        """
        select coalesce((
          select item.id
          from fdw_stage.case_event_significant_items item
          where item.case_event_id <= :cutoverEventHwm
          order by item.id desc
          limit 1
        ), 0)
        """,
        baseParams().addValue("cutoverEventHwm", cutoverEventHwm),
        Long.class
    ));
    return hwm == null ? 0 : hwm;
  }

  private long nextFixedSignificantItemWindowEnd(long significantItemsHwm, long targetSignificantItemsHwm) {
    long itemIdWindowSize = options.significantItemIdWindowSize();
    long windowEnd = significantItemsHwm > Long.MAX_VALUE - itemIdWindowSize
        ? Long.MAX_VALUE
        : significantItemsHwm + itemIdWindowSize;
    return Math.min(windowEnd, targetSignificantItemsHwm);
  }

  private void updateSignificantItemsProgressHighWaterMark(long significantItemsHwm) {
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set significant_items_hwm = greatest(significant_items_hwm, :significantItemsHwm),
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of(
            "taskName", options.taskName(),
            "significantItemsHwm", significantItemsHwm
        )
    );
  }

  private void validateSignificantItemsHighWaterMarkHasNotRegressed(long currentSignificantItemsHwm) {
    long progressSignificantItemsHwm = significantItemsProgressHighWaterMark();
    if (progressSignificantItemsHwm > currentSignificantItemsHwm) {
      throw new CcdDataMigrationException(
          "CCD data migration significant-item high-water mark regressed taskName=" + options.taskName()
              + " significantItemsHwm=" + progressSignificantItemsHwm
              + " currentSignificantItemsHwm=" + currentSignificantItemsHwm
              + ". A lower source significant item may have become visible after progress advanced; "
              + "investigate before retrying."
      );
    }
  }

  private long effectiveSourceEventHighWaterMark(long targetEventHwm) {
    long progressEventHwm = sourceEventProgressHighWaterMark();
    if (progressEventHwm > 0) {
      return progressEventHwm;
    }

    long localEventHwm = localEventHighWaterMark();
    if (localEventHwm > 0) {
      throw new CcdDataMigrationException(
          "CCD data migration target already contains migrated events but source_event_hwm is zero"
              + " taskName=" + options.taskName()
              + " localEventHwm=" + localEventHwm
              + " targetEventHwm=" + targetEventHwm
      );
    }
    return 0;
  }

  private long nextFixedEventWindowEnd(long sourceEventHwm, long targetEventHwm) {
    long eventIdWindowSize = options.eventIdWindowSize();
    long windowEnd = sourceEventHwm > Long.MAX_VALUE - eventIdWindowSize
        ? Long.MAX_VALUE
        : sourceEventHwm + eventIdWindowSize;
    return Math.min(windowEnd, targetEventHwm);
  }

  private long captureCutoverEventHighWaterMark() {
    long hwm = sourceEventHighWaterMark();
    validateSourceEventHighWaterMarkHasNotRegressed(hwm);
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

  private void reopenPreload() {
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set status = :status,
            cutover_event_hwm = null,
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of("taskName", options.taskName(), "status", STATUS_PRELOAD)
    );
  }

  private void prepareCompleteProgressForNextCutover(long completedCutoverEventHwm) {
    db.update(
        """
        update ccd.ccd_data_migration_progress
        set source_event_hwm = greatest(source_event_hwm, :completedCutoverEventHwm),
            updated_at = now() at time zone 'UTC'
        where task_name = :taskName
        """,
        Map.of(
            "taskName", options.taskName(),
            "completedCutoverEventHwm", completedCutoverEventHwm
        )
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

  private void completeCutover(long cutoverEventHwm, LocalDateTime stopAt) {
    transaction.execute(status -> {
      applyMigrationStatementTimeout();
      validateFinal(cutoverEventHwm, stopAt, new BatchBudget(options.maxBatchesPerRun()));
      enableElasticsearchQueueTrigger();
      markComplete();
      return null;
    });
  }

  private void validateFinal(long eventHwm, LocalDateTime stopAt, BatchBudget batchBudget) {
    log.info("CCD data migration final counts taskName={} caseData={}", options.taskName(), queryCounts("case_data"));
    log.info("CCD data migration final counts taskName={} caseEvent={}", options.taskName(), queryCounts("case_event"));
    log.info(
        "CCD data migration final counts taskName={} caseEventSignificantItems={}",
        options.taskName(),
        queryCounts("case_event_significant_items")
    );
    ValidationTotals eventValidationTotals = validateEventBatches(eventHwm, stopAt, batchBudget);
    ValidationTotals significantItemValidationTotals = validateSignificantItemBatches(stopAt, batchBudget);
    if (!eventValidationTotals.caughtUp() || !significantItemValidationTotals.caughtUp()) {
      throw new CcdDataMigrationException(
          "CCD data migration validation did not catch up before cutover completion taskName=" + options.taskName()
              + " eventHwm=" + eventHwm
      );
    }
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
          where jurisdiction = :sourceJurisdiction
            and case_type_id in (:caseTypeIds)
          group by case_type_id
          order by case_type_id
          """;
      case "case_event" -> """
          select ce.case_type_id, count(*) as count
          from ccd.case_event ce
          join ccd.case_data cd on cd.id = ce.case_data_id
          where cd.jurisdiction = :sourceJurisdiction
            and cd.case_type_id in (:caseTypeIds)
            and ce.case_type_id in (:caseTypeIds)
          group by ce.case_type_id
          order by ce.case_type_id
          """;
      case "case_event_significant_items" -> """
          select cd.case_type_id, count(*) as count
          from ccd.case_event_significant_items item
          join ccd.case_event ce on ce.id = item.case_event_id
          join ccd.case_data cd on cd.id = ce.case_data_id
          where cd.jurisdiction = :sourceJurisdiction
            and cd.case_type_id in (:caseTypeIds)
            and ce.case_type_id in (:caseTypeIds)
          group by cd.case_type_id
          order by cd.case_type_id
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
    db.getJdbcTemplate().execute("""
        select setval(
          'ccd.case_event_significant_items_id_seq'::regclass,
          (select coalesce(max(id), 1) from ccd.case_event_significant_items),
          true
        )
        """);
  }

  private void prepareElasticsearchQueueForMigration() {
    disableElasticsearchQueueTrigger();
    deleteElasticsearchQueueRowsForMigration();
  }

  private void deleteElasticsearchQueueRowsForMigration() {
    db.update(
        """
        delete from ccd.es_queue queue
        using ccd.case_data case_data
        where queue.reference = case_data.reference
          and case_data.jurisdiction = :sourceJurisdiction
          and case_data.case_type_id in (:caseTypeIds)
        """,
        baseParams()
    );
  }

  private long countElasticsearchQueueRows() {
    Long rows = db.queryForObject(
        """
        select count(*)
        from ccd.es_queue queue
        join ccd.case_data case_data on case_data.reference = queue.reference
        where case_data.jurisdiction = :sourceJurisdiction
          and case_data.case_type_id in (:caseTypeIds)
        """,
        baseParams(),
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
            'source_event_hwm',
            'significant_items_hwm',
            'validated_event_hwm',
            'validated_significant_items_hwm',
            'created_at',
            'updated_at'
          )
        """,
        Map.of("schema", TARGET_SCHEMA),
        Integer.class
    );
    if (columnCount == null || columnCount != 10) {
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
               cutover_event_hwm,
               source_event_hwm,
               significant_items_hwm,
               validated_event_hwm,
               validated_significant_items_hwm
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
        cutoverEventHwm,
        rs.getLong("source_event_hwm"),
        rs.getLong("significant_items_hwm"),
        rs.getLong("validated_event_hwm"),
        rs.getLong("validated_significant_items_hwm")
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

    ensureFdwTables();

    Integer missingTables = db.queryForObject(
        """
        select 3 - count(*)
        from (
          select c.relname
          from pg_foreign_table ft
          join pg_class c on c.oid = ft.ftrelid
          join pg_namespace n on n.oid = c.relnamespace
          where n.nspname = :fdwSchema
            and c.relname in ('case_data', 'case_event', 'case_event_significant_items')
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

    grantAdditionalFdwSelectAccess();
  }

  private void ensureFdwTables() {
    db.getJdbcTemplate().execute("""
        do $$
        declare
          fdw_server text;
          source_schema text;
          fetch_size text;
        begin
          if to_regclass('fdw_stage.case_data') is not null
              and to_regclass('fdw_stage.case_event') is not null
              and to_regclass('fdw_stage.case_event_significant_items') is not null then
            return;
          end if;

          select s.srvname,
                 coalesce((
                   select split_part(option, '=', 2)
                   from unnest(ft.ftoptions) option
                   where split_part(option, '=', 1) = 'schema_name'
                 ), 'public'),
                 (
                   select split_part(option, '=', 2)
                   from unnest(ft.ftoptions) option
                   where split_part(option, '=', 1) = 'fetch_size'
                 )
          into fdw_server, source_schema, fetch_size
          from pg_foreign_table ft
          join pg_class c on c.oid = ft.ftrelid
          join pg_namespace n on n.oid = c.relnamespace
          join pg_foreign_server s on s.oid = ft.ftserver
          where n.nspname = 'fdw_stage'
            and c.relname in ('case_data', 'case_event', 'case_event_significant_items')
          order by case c.relname
            when 'case_event' then 1
            when 'case_data' then 2
            else 3
          end
          limit 1;

          if fdw_server is null then
            return;
          end if;
          fetch_size := coalesce(fetch_size, '10000');

          if to_regclass('fdw_stage.case_data') is null then
            execute format(
              'create foreign table fdw_stage.case_data (
                reference bigint,
                version integer,
                created_date timestamp without time zone,
                security_classification ccd.securityclassification,
                last_state_modified_date timestamp without time zone,
                resolved_ttl date,
                last_modified timestamp without time zone,
                jurisdiction varchar(255),
                case_type_id varchar(255),
                state varchar(255),
                data jsonb,
                supplementary_data jsonb,
                id bigint
              ) server %I options (schema_name %L, table_name %L, fetch_size %L)',
              fdw_server,
              source_schema,
              'case_data',
              fetch_size
            );
          end if;

          if to_regclass('fdw_stage.case_event') is null then
            execute format(
              'create foreign table fdw_stage.case_event (
                id bigint,
                created_date timestamp without time zone,
                security_classification ccd.securityclassification,
                case_data_id bigint,
                case_type_version integer,
                event_id varchar(70),
                summary varchar(1024),
                description varchar(65536),
                user_id varchar(64),
                case_type_id varchar(255),
                state_id varchar(255),
                data jsonb,
                user_first_name varchar(255),
                user_last_name varchar(255),
                event_name varchar(30),
                state_name varchar(255),
                proxied_by varchar(64),
                proxied_by_first_name varchar(255),
                proxied_by_last_name varchar(255)
              ) server %I options (schema_name %L, table_name %L, fetch_size %L)',
              fdw_server,
              source_schema,
              'case_event',
              fetch_size
            );
          end if;

          if to_regclass('fdw_stage.case_event_significant_items') is null then
            execute format(
              'create foreign table fdw_stage.case_event_significant_items (
                id bigint,
                description varchar(64),
                "type" text,
                url text,
                case_event_id bigint
              ) server %I options (schema_name %L, table_name %L, fetch_size %L)',
              fdw_server,
              source_schema,
              'case_event_significant_items',
              fetch_size
            );
          end if;

          grant select on
            fdw_stage.case_data,
            fdw_stage.case_event,
            fdw_stage.case_event_significant_items
          to current_user;
        end $$;
        """);
  }

  private void grantAdditionalFdwSelectAccess() {
    String grantee = options.fdwAdditionalSelectGrantee();
    if (grantee == null) {
      return;
    }

    log.info("Granting CCD data migration FDW select access to {}", grantee);
    List<String> fdwServers = db.queryForList("""
        select s.srvname
        from pg_foreign_table ft
        join pg_class c on c.oid = ft.ftrelid
        join pg_namespace n on n.oid = c.relnamespace
        join pg_foreign_server s on s.oid = ft.ftserver
        where n.nspname = 'fdw_stage'
          and c.relname in ('case_data', 'case_event', 'case_event_significant_items')
        limit 1
        """,
        Map.of(),
        String.class
    );
    if (fdwServers.isEmpty()) {
      throw new CcdDataMigrationException("No FDW server found for fdw_stage tables");
    }

    String granteeIdentifier = quoteSqlIdentifier(grantee);
    String fdwServerIdentifier = quoteSqlIdentifier(fdwServers.getFirst());
    db.getJdbcTemplate().execute("grant usage on schema fdw_stage to " + granteeIdentifier);
    db.getJdbcTemplate().execute(
        "grant usage on foreign server " + fdwServerIdentifier + " to " + granteeIdentifier
    );
    db.getJdbcTemplate().execute("""
        grant select on
          fdw_stage.case_data,
          fdw_stage.case_event,
          fdw_stage.case_event_significant_items
        to """ + granteeIdentifier);
  }

  private String quoteSqlIdentifier(String identifier) {
    return db.queryForObject(
        "select quote_ident(:identifier)",
        Map.of("identifier", identifier),
        String.class
    );
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

  private LocalDateTime sourceEventCreatedBefore() {
    return LocalDateTime.now(clock).minus(options.sourceEventSafetyWindow());
  }

  private boolean isTimeLimitReached(LocalDateTime stopAt) {
    return stopAt != null && !LocalDateTime.now(clock).isBefore(stopAt);
  }

  private <T> T withMigrationStatementTimeout(Supplier<T> operation) {
    return transaction.execute(status -> {
      applyMigrationStatementTimeout();
      return operation.get();
    });
  }

  private void applyMigrationStatementTimeout() {
    if (options.statementTimeout() == null) {
      return;
    }
    db.getJdbcTemplate().queryForObject(
        "select set_config('statement_timeout', ?, true)",
        String.class,
        options.statementTimeout().toMillis() + "ms"
    );
  }

  private MapSqlParameterSource eventBatchParams(long batchStartEventId, long batchEndEventHwm) {
    return baseParams()
        .addValue("batchStartEventId", batchStartEventId)
        .addValue("batchEndEventHwm", batchEndEventHwm);
  }

  private MapSqlParameterSource baseParams() {
    return new MapSqlParameterSource()
        .addValue("caseTypeIds", options.caseTypeIds())
        .addValue("sourceJurisdiction", options.sourceJurisdiction());
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

  private record Progress(
      String configHash,
      String status,
      Long cutoverEventHwm,
      long sourceEventHwm,
      long significantItemsHwm,
      long validatedEventHwm,
      long validatedSignificantItemsHwm
  ) {
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

  private record SignificantItemCopyTotals(long batches, long items, boolean caughtUp, boolean stoppedByTimeLimit) {
    private SignificantItemCopyTotals() {
      this(0, 0, false, false);
    }

    private SignificantItemCopyTotals plus(int copiedItems) {
      return new SignificantItemCopyTotals(batches + 1, items + copiedItems, caughtUp, stoppedByTimeLimit);
    }

    private SignificantItemCopyTotals markCaughtUp() {
      return new SignificantItemCopyTotals(batches, items, true, stoppedByTimeLimit);
    }

    private SignificantItemCopyTotals markStoppedByTimeLimit() {
      return new SignificantItemCopyTotals(batches, items, caughtUp, true);
    }
  }

  private record ValidationTotals(long batches, boolean caughtUp, boolean stoppedByTimeLimit) {
    private ValidationTotals() {
      this(0, false, false);
    }

    private ValidationTotals plus() {
      return new ValidationTotals(batches + 1, caughtUp, stoppedByTimeLimit);
    }

    private ValidationTotals markCaughtUp() {
      return new ValidationTotals(batches, true, stoppedByTimeLimit);
    }

    private ValidationTotals markStoppedByTimeLimit() {
      return new ValidationTotals(batches, caughtUp, true);
    }
  }

  private static final class BatchBudget {
    private int remaining;
    private int consumed;

    private BatchBudget(int remaining) {
      this.remaining = remaining;
    }

    private boolean hasRemaining() {
      return remaining > 0;
    }

    private void consume() {
      remaining--;
      consumed++;
    }

    private int consumed() {
      return consumed;
    }
  }
}
