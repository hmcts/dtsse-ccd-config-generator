package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.DateTimeException;

/** Thrown for a date in a year whose bank holidays are not known, rather than treating it as having none. */
public class UnknownBankHolidaysException extends DateTimeException {

  private static final long serialVersionUID = 1L;

  UnknownBankHolidaysException(String message) {
    super(message);
  }
}
