package uk.gov.hmcts.ccd.sdk.bankholidays;

/**
 * Thrown for a date in a year GOV.UK has not published a division's bank holidays for, rather than
 * treating the date as having none.
 */
public class UnknownBankHolidaysException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  UnknownBankHolidaysException(String message) {
    super(message);
  }
}
