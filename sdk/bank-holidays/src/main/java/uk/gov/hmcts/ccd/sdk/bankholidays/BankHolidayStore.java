package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The bank holidays in the {@code bank_holidays} schema. Writes replace whole years, in their own
 * transaction under a lock so that instances take turns; a year stored is never removed, so the
 * dates bank holidays are known for never shrink.
 */
class BankHolidayStore {

  /** How old the last successful fetch may be before an instance fetches again. */
  static final Duration MAX_AGE = Duration.ofDays(1);
  /** How long a fetch started, which may have failed, holds off the next. */
  static final Duration RETRY_AFTER = Duration.ofHours(1);

  private static final String LOCK = "select pg_advisory_xact_lock(hashtext('ccd-sdk-bank-holidays'))";

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final List<BankHoliday> seed = GovUkBankHolidays.seed();

  BankHolidayStore(DataSource dataSource) {
    this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * The stored bank holidays, first seeded from the SDK's copy of GOV.UK's feed when it reaches a
   * later year than they do, as it does before the first fetch or in an SDK newer than the last.
   */
  List<BankHoliday> load() {
    transaction.executeWithoutResult(status -> {
      jdbc.getJdbcTemplate().execute(LOCK);
      LocalDate stored = jdbc.getJdbcTemplate()
          .queryForObject("select max(date) from bank_holidays.bank_holiday", LocalDate.class);
      if (stored == null || latest(seed).getYear() > stored.getYear()) {
        write(seed);
      }
    });
    return jdbc.query("select division, date, title, notes from bank_holidays.bank_holiday", (row, i) ->
        new BankHoliday(Division.valueOf(row.getString("division")), row.getDate("date").toLocalDate(),
            row.getString("title"), row.getString("notes")));
  }

  /** Whether this instance is to fetch now, which claims the fetch so that others do not. */
  boolean claimFetch() {
    Instant now = Instant.now();
    return jdbc.update("update bank_holidays.fetch_state set last_attempt_at = :now "
            + "where (fetched_at is null or fetched_at <= :staleBefore) "
            + "and (last_attempt_at is null or last_attempt_at <= :retryBefore)",
        new MapSqlParameterSource("now", Timestamp.from(now))
            .addValue("staleBefore", Timestamp.from(now.minus(MAX_AGE)))
            .addValue("retryBefore", Timestamp.from(now.minus(RETRY_AFTER)))) == 1;
  }

  void fetched(List<BankHoliday> holidays) {
    transaction.executeWithoutResult(status -> {
      jdbc.getJdbcTemplate().execute(LOCK);
      write(holidays);
      jdbc.update("update bank_holidays.fetch_state set fetched_at = :now, last_attempt_error = null",
          Map.of("now", Timestamp.from(Instant.now())));
    });
  }

  void fetchFailed(String error) {
    jdbc.update("update bank_holidays.fetch_state set last_attempt_error = :error", Map.of("error", error));
  }

  /** Replaces the bank holidays of the divisions and years the given ones are in. */
  private void write(List<BankHoliday> holidays) {
    jdbc.batchUpdate("delete from bank_holidays.bank_holiday where division = :division "
            + "and date >= make_date(:year, 1, 1) and date < make_date(:year + 1, 1, 1)",
        holidays.stream().map(holiday -> Map.of("division", holiday.division().name(), "year",
                holiday.date().getYear())).distinct()
            .map(MapSqlParameterSource::new).toArray(SqlParameterSource[]::new));
    jdbc.batchUpdate("insert into bank_holidays.bank_holiday (division, date, title, notes) "
            + "values (:division, :date, :title, :notes)",
        holidays.stream().map(holiday -> new MapSqlParameterSource()
            .addValue("division", holiday.division().name())
            .addValue("date", Date.valueOf(holiday.date()))
            .addValue("title", holiday.title())
            .addValue("notes", holiday.notes())).toArray(SqlParameterSource[]::new));
  }

  private static LocalDate latest(List<BankHoliday> holidays) {
    return holidays.stream().map(BankHoliday::date).max(Comparator.naturalOrder()).orElseThrow();
  }
}
