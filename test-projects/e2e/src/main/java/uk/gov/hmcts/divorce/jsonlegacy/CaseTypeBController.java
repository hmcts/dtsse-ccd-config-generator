package uk.gov.hmcts.divorce.jsonlegacy;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/case-type-b")
public class CaseTypeBController extends BaseJsonLegacyController {

  public CaseTypeBController(NamedParameterJdbcTemplate db) {
    super(db);
  }
}
