package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilIntervalData.MUST_BE_WORKING_DAY_NEXT;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilIntervalData.MUST_BE_WORKING_DAY_NO;
import static uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilIntervalData.MUST_BE_WORKING_DAY_PREVIOUS;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.DelayUntilRequest;

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
    if (delayUntilIntervalData.getMustBeWorkingDay().equalsIgnoreCase(MUST_BE_WORKING_DAY_NO)) {
      return calculatedDate;
    }
    WorkingDayIndicator.WorkingDays workingDays = workingDaysOf(delayUntilIntervalData);
    if (workingDays.isWorkingDay(calculatedDate)) {
      return calculatedDate;
    }
    if (delayUntilIntervalData.getMustBeWorkingDay().equalsIgnoreCase(MUST_BE_WORKING_DAY_NEXT)) {
      return workingDays.next(calculatedDate);
    }
    if (delayUntilIntervalData.getMustBeWorkingDay().equalsIgnoreCase(MUST_BE_WORKING_DAY_PREVIOUS)) {
      return workingDays.previous(calculatedDate);
    }
    return calculatedDate;
  }

  private LocalDate skipNonWorkingDays(DelayUntilIntervalData delayUntilIntervalData, LocalDate localDate) {
    WorkingDayIndicator.WorkingDays workingDays = workingDaysOf(delayUntilIntervalData);
    LocalDate calculatedDate = localDate;
    for (long counter = Math.abs(delayUntilIntervalData.getIntervalDays()); counter > 0; counter--) {
      calculatedDate = delayUntilIntervalData.getIntervalDays() < 0
          ? workingDays.previous(calculatedDate)
          : workingDays.next(calculatedDate);
    }
    return calculatedDate;
  }

  private WorkingDayIndicator.WorkingDays workingDaysOf(DelayUntilIntervalData delayUntilIntervalData) {
    return workingDayIndicator.of(
        delayUntilIntervalData.getNonWorkingCalendars(),
        delayUntilIntervalData.getNonWorkingDaysOfWeek());
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
