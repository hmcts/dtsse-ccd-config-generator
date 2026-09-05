package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.util.List;

/**
 * The emitted annotation patch: the git-apply-able unified diff text plus, per touched file, its
 * before/after content (so the round-trip proof can apply the change in-memory without shelling out
 * to {@code git}).
 */
public final class RetrofitPatch {

  private final String unifiedDiff;
  private final List<FilePatch> files;

  RetrofitPatch(String unifiedDiff, List<FilePatch> files) {
    this.unifiedDiff = unifiedDiff;
    this.files = files;
  }

  /**
   * The full unified diff, {@code git apply}-able from the model source root.
   *
   * @return the unified diff text (empty when nothing changed)
   */
  public String unifiedDiff() {
    return unifiedDiff;
  }

  /**
   * The per-file before/after content for every file the patch touches.
   *
   * @return the file patches
   */
  public List<FilePatch> files() {
    return files;
  }

  /** One file the patch edits: its source-root-relative path and its before/after text. */
  public static final class FilePatch {
    private final String relativePath;
    private final String originalContent;
    private final String patchedContent;

    FilePatch(String relativePath, String originalContent, String patchedContent) {
      this.relativePath = relativePath;
      this.originalContent = originalContent;
      this.patchedContent = patchedContent;
    }

    public String relativePath() {
      return relativePath;
    }

    public String originalContent() {
      return originalContent;
    }

    public String patchedContent() {
      return patchedContent;
    }
  }
}
