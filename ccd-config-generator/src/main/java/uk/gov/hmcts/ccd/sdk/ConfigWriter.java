package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

public interface ConfigWriter<T, S, R extends HasRole> {
  void write(File outputFolder, ResolvedCCDConfig<T, S, R> config);
}
