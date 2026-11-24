package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * Thrown for a date in a year GOV.UK has not published a division's bank holidays for, rather than
 * treating the date as having none.
 */
public class UnknownBankHolidaysException extends DateTimeException {

  private static final long serialVersionUID = 1L;

  private final Division division;
  private final transient LocalDate date;

  UnknownBankHolidaysException(Division division, LocalDate date, String message) {
    super(message);
    this.division = division;
    this.date = date;
  }

  public Division division() {
    return division;
  }

  /** The date asked about, or null when none of the division's bank holidays are known. */
  public LocalDate date() {
    return date;
  }
}
