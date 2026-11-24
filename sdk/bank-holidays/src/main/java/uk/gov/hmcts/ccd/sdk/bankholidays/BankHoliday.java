package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A bank holiday as GOV.UK publishes it.
 *
 * @param notes for example {@code Substitute day}; empty when GOV.UK gives none
 */
public record BankHoliday(Division division, LocalDate date, String title, String notes) {

  public BankHoliday {
    Objects.requireNonNull(division, "division");
    Objects.requireNonNull(date, "date");
    Objects.requireNonNull(title, "title");
    notes = notes == null ? "" : notes;
  }
}
