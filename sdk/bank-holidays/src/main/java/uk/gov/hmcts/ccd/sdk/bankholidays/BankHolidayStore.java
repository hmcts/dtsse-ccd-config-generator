package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.sql.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The bank holidays in the {@code bank_holidays} schema. Writes are made under a lock, so instances
 * writing at once take turns.
 */
class BankHolidayStore {

  private static final String LOCK = "select pg_advisory_xact_lock(hashtext('ccd-sdk-bank-holidays'))";

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  BankHolidayStore(DataSource dataSource) {
    this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** The stored bank holidays, first adding the years the SDK's copy of GOV.UK's feed has that they lack. */
  List<BankHoliday> load() {
    transaction.executeWithoutResult(status -> {
      jdbc.getJdbcTemplate().execute(LOCK);
      Set<String> stored = new HashSet<>(jdbc.getJdbcTemplate().queryForList(
          "select distinct division || ':' || extract(year from date)::int from bank_holidays.bank_holiday",
          String.class));
      List<BankHoliday> missing = GovUkBankHolidays.seed().stream()
          .filter(holiday -> !stored.contains(holiday.division().name() + ":" + holiday.date().getYear()))
          .toList();
      if (!missing.isEmpty()) {
        write(missing);
      }
    });
    return jdbc.query("select division, date, title, notes from bank_holidays.bank_holiday", (row, i) ->
        new BankHoliday(Division.valueOf(row.getString("division")), row.getDate("date").toLocalDate(),
            row.getString("title"), row.getString("notes")));
  }

  /** Replaces the bank holidays of the divisions and years the given ones are in, keeping the rest. */
  void save(List<BankHoliday> holidays) {
    transaction.executeWithoutResult(status -> {
      jdbc.getJdbcTemplate().execute(LOCK);
      write(holidays);
    });
  }

  private void write(List<BankHoliday> holidays) {
    jdbc.batchUpdate("delete from bank_holidays.bank_holiday where division = :division "
            + "and date >= make_date(:year, 1, 1) and date < make_date(:year + 1, 1, 1)",
        holidays.stream().map(holiday -> Map.of("division", holiday.division().name(),
                "year", holiday.date().getYear())).distinct()
            .map(MapSqlParameterSource::new).toArray(SqlParameterSource[]::new));
    jdbc.batchUpdate("insert into bank_holidays.bank_holiday (division, date, title, notes) "
            + "values (:division, :date, :title, :notes)",
        holidays.stream().map(holiday -> new MapSqlParameterSource()
            .addValue("division", holiday.division().name())
            .addValue("date", Date.valueOf(holiday.date()))
            .addValue("title", holiday.title())
            .addValue("notes", holiday.notes())).toArray(SqlParameterSource[]::new));
  }
}
