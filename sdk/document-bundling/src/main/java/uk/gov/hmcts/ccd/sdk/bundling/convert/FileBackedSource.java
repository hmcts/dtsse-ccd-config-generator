package uk.gov.hmcts.ccd.sdk.bundling.convert;

import java.nio.file.Path;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;

public interface FileBackedSource extends ResolvedDocument {
  Path file();
}
