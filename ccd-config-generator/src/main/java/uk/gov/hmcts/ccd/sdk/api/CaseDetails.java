package uk.gov.hmcts.ccd.sdk.api;

import java.time.LocalDateTime;
import lombok.Data;
import uk.gov.hmcts.reform.ccd.client.model.Classification;

@Data
public class CaseDetails<T, S> {
  private Long id;

  private String jurisdiction;

  private String caseTypeId;

  private LocalDateTime createdDate;

  private LocalDateTime lastModified;

  private S state;

  private Integer lockedBy;

  private Integer securityLevel;

  private T data;

  private Classification securityClassification;

  private String callbackResponseStatus;

}
