package uk.gov.hmcts.divorce.jsonlegacy;

import java.util.List;
import java.util.Map;
import lombok.Data;
import uk.gov.hmcts.divorce.divorcecase.model.ImmutableCaseData;

@Data
public class LegacyJsonDataModel {

  private String note;

  private String setInMidEvent;

  private String setInAboutToSubmit;

  private ImmutableCaseData immutableCaseData;

  private List<Map<String, Object>> documentCollection;
}
