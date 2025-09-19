package uk.gov.hmcts.ccd.sdk.generator;

import java.io.File;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

@Component
class WorkBasketGenerator<T, S, R extends HasRole> extends SearchFieldAndResultGenerator<T, S, R> {

  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    generateFields(root, config.getCaseType(), config.getWorkBasketInputFields(), "WorkBasketInputFields");
    generateFields(root, config.getCaseType(), config.getWorkBasketResultFields(), "WorkBasketResultFields");
  }

}

