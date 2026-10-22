package uk.gov.hmcts.ccd.sdk.converter.emit;

import com.palantir.javapoet.JavaFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single write path for generated sources, and the layout post-pass applied on the way out.
 *
 * <p>JavaPoet lays out every field/type/enum-constant annotation that carries a named member over
 * several lines, one member per line, whatever its length:
 * <pre>
 *     &#64;JsonUnwrapped(
 *             prefix = "legal"
 *     )
 * </pre>
 * It has no option to inline them (its {@code inline} flag is reserved for parameter annotations),
 * so {@link #collapseAnnotations(String)} rewrites each such multi-line annotation onto one line —
 * {@code @JsonUnwrapped(prefix = "legal")} — when that line, indent included, fits
 * {@link #MAX_LINE_LENGTH} columns, and leaves it multi-line otherwise. Only the line breaks and
 * continuation indents between tokens are touched: the collapsed text equals the original with
 * whitespace ignored, and nothing inside a string literal changes.
 *
 * <p>The pass is conservative: it only starts on a line that is in plain code (not inside a text
 * block or a comment) and begins with an annotation (optionally as a member value,
 * {@code name = @Inner(}), and it gives up on a block that contains a comment or a text block, or
 * whose brackets do not balance.
 */
public final class GeneratedSourceFormatter {

  /**
   * The line-length ceiling a collapsed annotation must fit. The converter's own checkstyle limit,
   * which is also the tightest {@code LineLength} among the services it targets (others allow
   * 140-300).
   */
  public static final int MAX_LINE_LENGTH = 120;

  /**
   * An annotation, possibly the value of a member ({@code inner = &#64;Inner(}), opening its member
   * list.
   */
  private static final Pattern ANNOTATION_START =
      Pattern.compile("^\\s*(?:[A-Za-z_$][\\w$]*\\s*=\\s*)?(@[A-Za-z_$][\\w$.]*\\s*\\()");

  private GeneratedSourceFormatter() {
  }

  /**
   * Writes {@code file} under {@code directory} exactly where {@link JavaFile#writeTo(Path)} would
   * (package directories, {@code <TypeName>.java}, UTF-8), with {@link #format(JavaFile)} applied.
   *
   * @param file the generated file
   * @param directory the source root
   * @return the path written
   * @throws IOException when the file cannot be written
   */
  public static Path writeTo(JavaFile file, Path directory) throws IOException {
    Path packageDir = directory;
    if (!file.packageName().isEmpty()) {
      for (String part : file.packageName().split("\\.")) {
        packageDir = packageDir.resolve(part);
      }
    }
    Files.createDirectories(packageDir);
    Path out = packageDir.resolve(file.typeSpec().name() + ".java");
    Files.writeString(out, format(file), StandardCharsets.UTF_8);
    return out;
  }

  /**
   * The source text of {@code file} as the converter writes it.
   *
   * @param file the generated file
   * @return the formatted source
   */
  public static String format(JavaFile file) {
    return collapseAnnotations(file.toString());
  }

  /**
   * Collapses every multi-line annotation in {@code source} that fits {@link #MAX_LINE_LENGTH}.
   *
   * @param source Java source text
   * @return the source with short annotations on one line
   */
  public static String collapseAnnotations(String source) {
    return collapseAnnotations(source, MAX_LINE_LENGTH);
  }

  /**
   * Collapses every multi-line annotation in {@code source} whose single-line form, indent
   * included, is at most {@code maxLineLength} columns.
   *
   * @param source Java source text
   * @param maxLineLength the column limit a collapsed line must fit
   * @return the source with short annotations on one line
   */
  public static String collapseAnnotations(String source, int maxLineLength) {
    String[] lines = source.split("\n", -1);
    boolean[] startsInCode = lineStartsInCode(source, lines.length);
    List<String> out = new ArrayList<>(lines.length);
    int i = 0;
    while (i < lines.length) {
      String collapsed = null;
      int end = -1;
      Matcher m = startsInCode[i] ? ANNOTATION_START.matcher(lines[i]) : null;
      if (m != null && m.find()) {
        end = findAnnotationEnd(lines, i, m.start(1));
        if (end > i) {
          collapsed = join(lines, i, end);
          if (collapsed.length() > maxLineLength) {
            collapsed = null;
          }
        }
      }
      if (collapsed != null) {
        out.add(collapsed);
        i = end + 1;
      } else {
        // Too long (or not an annotation): keep the line and carry on from the next one, so a
        // nested annotation inside an over-long outer one can still collapse on its own.
        out.add(lines[i]);
        i++;
      }
    }
    return String.join("\n", out);
  }

  /**
   * The index of the line on which the annotation starting at {@code lines[start]} column
   * {@code col} closes its member list, or -1 when it closes on its start line, never closes, or
   * the block holds anything this pass will not rewrite (a comment, a text block, an unterminated
   * literal, an unbalanced closing bracket).
   */
  private static int findAnnotationEnd(String[] lines, int start, int col) {
    int depth = 0;
    for (int ln = start; ln < lines.length; ln++) {
      String s = lines[ln];
      int c = ln == start ? col : 0;
      while (c < s.length()) {
        char ch = s.charAt(c);
        if (ch == '"') {
          if (s.startsWith("\"\"\"", c)) {
            return -1;
          }
          int close = endOfQuoted(s, c, '"');
          if (close < 0) {
            return -1;
          }
          c = close + 1;
          continue;
        }
        if (ch == '\'') {
          int close = endOfQuoted(s, c, '\'');
          if (close < 0) {
            return -1;
          }
          c = close + 1;
          continue;
        }
        if (ch == '/' && c + 1 < s.length() && (s.charAt(c + 1) == '/' || s.charAt(c + 1) == '*')) {
          return -1;
        }
        if (ch == '(' || ch == '{' || ch == '[') {
          depth++;
        } else if (ch == ')' || ch == '}' || ch == ']') {
          depth--;
          if (depth < 0) {
            return -1;
          }
          if (depth == 0) {
            return ln == start ? -1 : ln;
          }
        }
        c++;
      }
    }
    return -1;
  }

  /**
   * The index of the quote closing the literal opened at {@code open}, or -1 if it is unterminated.
   */
  private static int endOfQuoted(String s, int open, char quote) {
    int i = open + 1;
    while (i < s.length()) {
      char ch = s.charAt(i);
      if (ch == '\\') {
        i += 2;
      } else if (ch == quote) {
        return i;
      } else {
        i++;
      }
    }
    return -1;
  }

  /**
   * Joins {@code lines[start..end]} onto one line: the start line keeps its indent, each later line
   * loses its leading indent and is separated by one space — or by nothing straight after an
   * opening bracket or before a closing bracket or comma, matching JavaPoet's own inline form
   * ({@code @A(x = 1, y = {B.class, C.class})}).
   */
  private static String join(String[] lines, int start, int end) {
    StringBuilder sb = new StringBuilder(lines[start].stripTrailing());
    for (int ln = start + 1; ln <= end; ln++) {
      String piece = lines[ln].strip();
      if (piece.isEmpty()) {
        continue;
      }
      char last = sb.charAt(sb.length() - 1);
      char first = piece.charAt(0);
      boolean tight = last == '(' || last == '{' || last == '['
          || first == ')' || first == '}' || first == ']' || first == ',';
      if (!tight) {
        sb.append(' ');
      }
      sb.append(piece);
    }
    return sb.toString();
  }

  /**
   * For each line, whether it starts in plain code rather than inside a text block or a block
   * comment (string/char literals and line comments cannot span lines, so they never matter here).
   */
  private static boolean[] lineStartsInCode(String src, int lineCount) {
    final int code = 0;
    final int string = 1;
    final int character = 2;
    final int lineComment = 3;
    final int blockComment = 4;
    final int textBlock = 5;
    boolean[] result = new boolean[lineCount];
    result[0] = true;
    int line = 0;
    int state = code;
    int i = 0;
    while (i < src.length()) {
      char ch = src.charAt(i);
      if (ch == '\n') {
        if (state == string || state == character || state == lineComment) {
          state = code;
        }
        line++;
        result[line] = state == code;
        i++;
        continue;
      }
      boolean escapable = state == string || state == character || state == textBlock;
      if (escapable && ch == '\\') {
        // Skip the escaped char, but never a newline (a text block's line continuation): the
        // newline branch above must still count it.
        i += i + 1 < src.length() && src.charAt(i + 1) != '\n' ? 2 : 1;
        continue;
      }
      if (state == code) {
        if (src.startsWith("\"\"\"", i)) {
          state = textBlock;
          i += 3;
          continue;
        } else if (ch == '"') {
          state = string;
        } else if (ch == '\'') {
          state = character;
        } else if (src.startsWith("//", i)) {
          state = lineComment;
          i += 2;
          continue;
        } else if (src.startsWith("/*", i)) {
          state = blockComment;
          i += 2;
          continue;
        }
      } else if ((state == string && ch == '"') || (state == character && ch == '\'')) {
        state = code;
      } else if (state == textBlock && src.startsWith("\"\"\"", i)) {
        state = code;
        i += 3;
        continue;
      } else if (state == blockComment && src.startsWith("*/", i)) {
        state = code;
        i += 2;
        continue;
      }
      i++;
    }
    return result;
  }
}
