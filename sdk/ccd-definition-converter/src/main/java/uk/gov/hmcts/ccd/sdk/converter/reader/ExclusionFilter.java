package uk.gov.hmcts.ccd.sdk.converter.reader;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;

/**
 * Mirrors the exclusion logic of the Node {@code ccd-definition-processor} tool.
 *
 * <p>The Node tool splits each relative path on {@code /} and tests whether ANY segment
 * matches ANY exclusion pattern (using {@code matcher.isMatch}, which supports glob wildcards).
 * This class replicates that per-segment matching behaviour using Java's
 * {@link FileSystems#getDefault() glob} {@link PathMatcher}.
 */
public final class ExclusionFilter {

  private ExclusionFilter() {
  }

  /**
   * Returns {@code true} when the given relative path should be excluded.
   *
   * <p>A path is excluded when at least one of its segments (split on {@code /}) matches at
   * least one of the supplied glob patterns. Matching is performed segment-by-segment, which
   * replicates the Node {@code file-utils.js} behaviour exactly.
   *
   * @param relativePath the path to test (forward-slash separated, relative to the sheet dir)
   * @param patterns glob patterns to test each segment against (e.g. {@code *-nonprod.json})
   * @return {@code true} if the path is excluded by any pattern
   */
  public static boolean isExcluded(String relativePath, List<String> patterns) {
    if (patterns.isEmpty()) {
      return false;
    }
    String[] segments = relativePath.split("/");
    for (String pattern : patterns) {
      PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
      for (String segment : segments) {
        if (matcher.matches(Paths.get(segment))) {
          return true;
        }
      }
    }
    return false;
  }
}
