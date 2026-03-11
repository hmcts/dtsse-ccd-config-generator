package uk.gov.hmcts.reform.optionallist;

import java.util.List;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.type.ListValue;

@Data
public class OptionalListCaseData {
  private List<ListValue<TestParty>> parties;
}
