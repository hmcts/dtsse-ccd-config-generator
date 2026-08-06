package uk.gov.hmcts.divorce.jsonlegacy;

import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class LegacyJsonDataModel {

  private String note;

  private String setInMidEvent;

  private String setInAboutToSubmit;

  private List<Map<String, Object>> documentCollection;
}
