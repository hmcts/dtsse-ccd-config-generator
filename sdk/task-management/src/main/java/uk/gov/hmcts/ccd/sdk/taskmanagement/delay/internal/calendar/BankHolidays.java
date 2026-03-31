package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
class BankHolidays {

  @JsonProperty("division")
  String division;

  @JsonProperty("events")
  List<EventDate> events;

  @JsonIgnoreProperties(ignoreUnknown = true)
  @Getter
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  static class EventDate {

    @JsonProperty("date")
    String date;
    @JsonProperty("working_day")
    boolean workingDay;

    @Override
    public boolean equals(Object object) {
      if (this == object) {
        return true;
      }
      if (object == null || getClass() != object.getClass()) {
        return false;
      }
      EventDate eventDate = (EventDate) object;
      return date.equals(eventDate.date);
    }

    @Override
    public int hashCode() {
      return Objects.hash(date);
    }
  }
}
