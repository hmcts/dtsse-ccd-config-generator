package uk.gov.hmcts.ccd.sdk.generator;

import java.io.File;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

public interface ConfigGenerator<T, S, R extends HasRole> {
  void write(File outputFolder, ResolvedCCDConfig<T, S, R> config);
}
