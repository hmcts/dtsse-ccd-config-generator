package uk.gov.hmcts.divorce.jsonlegacy;

import java.util.List;
import java.util.Map;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.divorce.divorcecase.model.ImmutableCaseData;

@Data
public class LegacyJsonDataModel {

  private String note;

  private String setInMidEvent;

  private String setInAboutToSubmit;

  private ImmutableCaseData immutableCaseData;

  @CCD(ignore = true)
  private Map<String, String> nullableValues;

  private List<Map<String, Object>> documentCollection;
}
