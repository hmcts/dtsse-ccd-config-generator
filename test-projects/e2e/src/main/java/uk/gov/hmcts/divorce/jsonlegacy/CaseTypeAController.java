package uk.gov.hmcts.divorce.jsonlegacy;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/case-type-a")
public class CaseTypeAController extends BaseJsonLegacyController {

  public CaseTypeAController(NamedParameterJdbcTemplate db) {
    super(db);
  }
}
