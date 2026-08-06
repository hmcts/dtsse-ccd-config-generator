package uk.gov.hmcts.ccd.sdk;

import java.util.Objects;

public record SystemCaseEvent(String id, String name) {

  public SystemCaseEvent {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
  }
}
