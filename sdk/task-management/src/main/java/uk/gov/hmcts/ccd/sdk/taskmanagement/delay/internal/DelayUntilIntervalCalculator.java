package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilIntervalData.MUST_BE_WORKING_DAY_NEXT;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilIntervalData.MUST_BE_WORKING_DAY_PREVIOUS;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.DelayUntilRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar.WorkingDayIndicator;

@RequiredArgsConstructor
class DelayUntilIntervalCalculator implements DelayUntilCalculator {

  private final WorkingDayIndicator workingDayIndicator;

  @Override
  public boolean supports(DelayUntilRequest delayUntilRequest) {
    return Optional.ofNullable(delayUntilRequest.getDelayUntilOrigin()).isPresent()
        && Optional.ofNullable(delayUntilRequest.getDelayUntil()).isEmpty();
  }

  @Override
  public LocalDateTime calculateDate(DelayUntilRequest delayUntilRequest) {
    DelayUntilIntervalData delayUntilIntervalData = readDateTypeOriginFields(delayUntilRequest);

    LocalDateTime referenceDate = delayUntilIntervalData.getReferenceDate();
    LocalDate localDate = referenceDate.toLocalDate();
    if (delayUntilIntervalData.isSkipNonWorkingDays()) {
      localDate = skipNonWorkingDays(delayUntilIntervalData, localDate);
    } else {
      localDate = considerAllDaysAsWorking(delayUntilIntervalData, localDate);
    }

    return calculateTime(delayUntilIntervalData.getDelayUntilTime(), referenceDate, localDate);
  }

  private LocalDate considerAllDaysAsWorking(DelayUntilIntervalData delayUntilIntervalData, LocalDate localDate) {
    LocalDate calculatedDate = localDate.plusDays(delayUntilIntervalData.getIntervalDays());
    boolean workingDay = workingDayIndicator.isWorkingDay(
        calculatedDate,
        delayUntilIntervalData.getNonWorkingCalendars(),
        delayUntilIntervalData.getNonWorkingDaysOfWeek()
    );
    if (delayUntilIntervalData.getMustBeWorkingDay()
        .equalsIgnoreCase(MUST_BE_WORKING_DAY_NEXT) && !workingDay) {
      calculatedDate = workingDayIndicator.getNextWorkingDay(
          calculatedDate,
          delayUntilIntervalData.getNonWorkingCalendars(),
          delayUntilIntervalData.getNonWorkingDaysOfWeek()
      );
    }
    if (delayUntilIntervalData.getMustBeWorkingDay()
        .equalsIgnoreCase(MUST_BE_WORKING_DAY_PREVIOUS) && !workingDay) {
      calculatedDate = workingDayIndicator.getPreviousWorkingDay(
          calculatedDate,
          delayUntilIntervalData.getNonWorkingCalendars(),
          delayUntilIntervalData.getNonWorkingDaysOfWeek()
      );
    }
    return calculatedDate;
  }

  private LocalDate skipNonWorkingDays(DelayUntilIntervalData delayUntilIntervalData, LocalDate localDate) {
    LocalDate calculatedDate = localDate;
    if (delayUntilIntervalData.getIntervalDays() < 0) {
      for (long counter = delayUntilIntervalData.getIntervalDays(); counter < 0; counter++) {
        calculatedDate = workingDayIndicator.getPreviousWorkingDay(
            calculatedDate,
            delayUntilIntervalData.getNonWorkingCalendars(),
            delayUntilIntervalData.getNonWorkingDaysOfWeek()
        );
      }
    } else {
      for (int counter = 0; counter < delayUntilIntervalData.getIntervalDays(); counter++) {
        calculatedDate = workingDayIndicator.getNextWorkingDay(
            calculatedDate,
            delayUntilIntervalData.getNonWorkingCalendars(),
            delayUntilIntervalData.getNonWorkingDaysOfWeek()
        );
      }
    }
    return calculatedDate;
  }

  private LocalDateTime calculateTime(String dateTypeTime, LocalDateTime referenceDate, LocalDate calculateDate) {
    LocalTime baseReferenceTime = referenceDate.toLocalTime();
    LocalDateTime dateTime = calculateDate.atTime(baseReferenceTime);

    if (Optional.ofNullable(dateTypeTime).isPresent()) {
      dateTime = calculateDate.atTime(LocalTime.parse(dateTypeTime));
    }
    return dateTime;
  }

  private DelayUntilIntervalData readDateTypeOriginFields(DelayUntilRequest delayUntilRequest) {
    return DelayUntilIntervalData.builder()
        .referenceDate(Optional.ofNullable(delayUntilRequest.getDelayUntilOrigin())
            .map(this::parseDateTime)
            .orElse(LocalDateTime.now()))
        .intervalDays(Optional.ofNullable(delayUntilRequest.getDelayUntilIntervalDays())
            .map(Long::valueOf)
            .orElse(0L))
        .nonWorkingCalendars(Optional.ofNullable(delayUntilRequest.getDelayUntilNonWorkingCalendar())
            .map(s -> s.split(","))
            .map(a -> Arrays.stream(a).map(String::trim).toArray(String[]::new))
            .map(Arrays::asList)
            .orElse(List.of(DEFAULT_NON_WORKING_CALENDAR)))
        .nonWorkingDaysOfWeek(Optional.ofNullable(delayUntilRequest.getDelayUntilNonWorkingDaysOfWeek())
            .map(s -> s.split(","))
            .map(a -> Arrays.stream(a).map(String::trim).toArray(String[]::new))
            .map(Arrays::asList)
            .orElse(List.of()))
        .skipNonWorkingDays(Optional.ofNullable(delayUntilRequest.getDelayUntilSkipNonWorkingDays())
            .orElse(true))
        .mustBeWorkingDay(Optional.ofNullable(delayUntilRequest.getDelayUntilMustBeWorkingDay())
            .orElse(MUST_BE_WORKING_DAY_NEXT))
        .delayUntilTime(delayUntilRequest.getDelayUntilTime())
        .build();
  }
}
