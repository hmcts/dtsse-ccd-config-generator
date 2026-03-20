package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

import feign.RequestLine;

interface BankHolidaysApi {

  @RequestLine("GET")
  BankHolidays retrieveAll();
}
