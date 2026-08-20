package uk.gov.hmcts.ccd.sdk.bundling.convert;

import java.nio.file.Path;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;

/**
 * A {@link ResolvedDocument} whose content is already spooled to a file in the job's restricted
 * temporary directory.
 *
 * <p>The rendering pipeline spools every resolved stream to disk before conversion, so the
 * sources it hands to handlers implement this interface; handlers that need a file (all the
 * built-ins do) read {@link #file()} directly instead of copying the content stream a second
 * time. The file is read-only input: a handler must never modify or delete it — the pipeline
 * owns its lifecycle and cleans it with the job.
 */
public interface FileBackedSource extends ResolvedDocument {

  /**
   * The spooled source file in the job's temporary directory.
   *
   * @return the file the content stream reads from
   */
  Path file();
}
