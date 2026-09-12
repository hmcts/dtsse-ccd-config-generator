package uk.gov.hmcts.example.cyclic;

import java.util.Date;
import lombok.Data;

/**
 * A case-type root whose complex-type graph exercises both cycle shapes the resolver has to
 * survive: a {@code java.util.Date} member (the JDK's internal Date -&gt; BaseCalendar$Date -&gt;
 * Era -&gt; CalendarDate -&gt; Era cycle, which real service models such as prl's Document carry) and
 * a mutually-referencing pair of the service's own types.
 */
@Data
public class CyclicCaseData {

  private Date createdOn;

  private Parent parent;
}
