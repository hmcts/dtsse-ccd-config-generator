package uk.gov.hmcts.example.missingcomplex;

import lombok.Builder;
import lombok.Data;

@Data
@Builder(toBuilder = true)
public class Applicant {
  private final String name;
}
