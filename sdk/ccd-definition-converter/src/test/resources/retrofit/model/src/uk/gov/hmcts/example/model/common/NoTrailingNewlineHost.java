package uk.gov.hmcts.example.model.common;

/**
 * Regression fixture for the "no newline at end of file" marker misplacement bug: the file has NO
 * trailing newline, and the annotated field ({@code orphanField}, unmatched Java -> ignore=true) is
 * followed by MORE unchanged lines than the diff's 3-line trailing context window, so the diff's
 * last hunk never reaches the file's true final line. The emitter must not stamp the
 * "\ No newline at end of file" marker onto that hunk's last printed (context) line — doing so
 * corrupts {@code git apply} by concatenating it with the line that actually follows.
 */
public class NoTrailingNewlineHost {

  private String orphanField;

  public String line1() {
    return "1";
  }

  public String line2() {
    return "2";
  }

  public String line3() {
    return "3";
  }

  public String line4() {
    return "4";
  }
}