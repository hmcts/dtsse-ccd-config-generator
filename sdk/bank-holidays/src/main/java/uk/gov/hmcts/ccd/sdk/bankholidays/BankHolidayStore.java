package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The bank holidays in the {@code bank_holidays} schema. Changes are made in their own transaction,
 * under a lock so that instances refreshing at once take turns.
 */
class BankHolidayStore {

  enum Source { GOV_UK, SEED }

  private static final String LOCK = "select pg_advisory_xact_lock(hashtext('ccd-sdk-bank-holidays'))";

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final List<BankHoliday> seed = GovUkBankHolidays.seed();
  private final LocalDate seedReaches = latest(seed);

  BankHolidayStore(DataSource dataSource, Clock clock) {
    this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  /**
   * The stored bank holidays, seeding them from the SDK's copy of GOV.UK's feed first when there
   * are none or the copy reaches later years, as an SDK newer than the last fetch can.
   */
  List<BankHoliday> load() {
    if (needsSeed()) {
      transaction.executeWithoutResult(status -> {
        jdbc.getJdbcTemplate().execute(LOCK);
        seedIfNeeded();
      });
    }
    return read();
  }

  /**
   * Whether this instance is to fetch from GOV.UK now, claiming the fetch so that other instances do
   * not: when no fetch has succeeded in the last maxAge, and none was started in the last retryAfter.
   */
  boolean claimFetch(Duration maxAge, Duration retryAfter) {
    return Boolean.TRUE.equals(transaction.execute(status -> {
      jdbc.getJdbcTemplate().execute(LOCK);
      seedIfNeeded();
      Instant now = clock.instant();
      return jdbc.update("update bank_holidays.fetch_state set last_attempt_at = :now "
              + "where (fetched_at is null or fetched_at <= :staleBefore) "
              + "and (last_attempt_at is null or last_attempt_at <= :retryBefore)",
          new MapSqlParameterSource("now", Timestamp.from(now))
              .addValue("staleBefore", Timestamp.from(now.minus(maxAge)))
              .addValue("retryBefore", Timestamp.from(now.minus(retryAfter)))) == 1;
    }));
  }

  /** Records a successful fetch, replacing the bank holidays if they changed. */
  void fetched(List<BankHoliday> holidays) {
    transaction.executeWithoutResult(status -> {
      jdbc.getJdbcTemplate().execute(LOCK);
      if (!new HashSet<>(holidays).equals(new HashSet<>(read()))) {
        write(holidays, Source.GOV_UK);
      }
      // GOV.UK publishing what the SDK's copy has makes them GOV.UK's.
      jdbc.update("update bank_holidays.fetch_state set source = :source", Map.of("source", Source.GOV_UK.name()));
      jdbc.update("update bank_holidays.fetch_state set fetched_at = :now, last_attempt_error = null",
          Map.of("now", now()));
    });
  }

  /** Records a failed fetch, keeping the bank holidays. */
  void fetchFailed(String error) {
    transaction.executeWithoutResult(status -> {
      jdbc.getJdbcTemplate().execute(LOCK);
      seedIfNeeded();
      jdbc.update("update bank_holidays.fetch_state set last_attempt_error = :error", Map.of("error", error));
    });
  }

  private boolean needsSeed() {
    return stored().filter(latest -> !seedReaches.isAfter(latest)).isEmpty();
  }

  private void seedIfNeeded() {
    if (needsSeed()) {
      write(seed, Source.SEED);
    }
  }

  private List<BankHoliday> read() {
    return jdbc.query("select division, date, title, notes from bank_holidays.bank_holiday", (row, i) ->
        new BankHoliday(Division.valueOf(row.getString("division")), row.getDate("date").toLocalDate(),
            row.getString("title"), row.getString("notes")));
  }

  private Optional<LocalDate> stored() {
    return Optional.ofNullable(jdbc.getJdbcTemplate().queryForObject(
        "select max(date) from bank_holidays.bank_holiday where exists (select 1 from bank_holidays.fetch_state)",
        LocalDate.class));
  }

  private void write(List<BankHoliday> holidays, Source source) {
    jdbc.getJdbcTemplate().update("delete from bank_holidays.bank_holiday");
    jdbc.batchUpdate("insert into bank_holidays.bank_holiday (division, date, title, notes) "
            + "values (:division, :date, :title, :notes)",
        holidays.stream().map(holiday -> (SqlParameterSource) new MapSqlParameterSource()
            .addValue("division", holiday.division().name())
            .addValue("date", Date.valueOf(holiday.date()))
            .addValue("title", holiday.title())
            .addValue("notes", holiday.notes())).toArray(SqlParameterSource[]::new));
    jdbc.update("insert into bank_holidays.fetch_state (id, source, updated_at) values (1, :source, :now) "
            + "on conflict (id) do update set source = excluded.source, updated_at = excluded.updated_at",
        Map.of("source", source.name(), "now", now()));
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  private static LocalDate latest(List<BankHoliday> holidays) {
    return holidays.stream().map(BankHoliday::date).max(Comparator.naturalOrder()).orElseThrow();
  }
}
