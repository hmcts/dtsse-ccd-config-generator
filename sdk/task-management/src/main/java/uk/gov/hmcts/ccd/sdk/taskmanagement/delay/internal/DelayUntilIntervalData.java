package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Builder(toBuilder = true)
@Getter
class DelayUntilIntervalData {

  static final String MUST_BE_WORKING_DAY_NEXT = "Next";
  static final String MUST_BE_WORKING_DAY_PREVIOUS = "Previous";
  static final String MUST_BE_WORKING_DAY_NO = "No";

  private Long intervalDays;
  private LocalDateTime referenceDate;
  private List<String> nonWorkingCalendars;
  private List<String> nonWorkingDaysOfWeek;
  private boolean skipNonWorkingDays;
  private String mustBeWorkingDay;
  private String delayUntilTime;
}
