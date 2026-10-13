package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Checks for Jackson 3 source and configuration usage without resolving dependencies. */
@DisableCachingByDefault(because = "Verification reports are inexpensive and contain project-relative diagnostics")
public abstract class JacksonCompatibilityCheck extends DefaultTask {
  private static final String JACKSON = "tools\\s*\\.\\s*jackson\\s*\\.\\s*";
  private static final Pattern JACKSON3_API = Pattern.compile(
      "\\b" + JACKSON + "(?:\\w+\\s*\\.\\s*)*[\\w$*]+|\\b" + JACKSON + "\\*");
  private static final Pattern JACKSON3_COORDINATE = Pattern.compile(
      "\\btools\\s*\\.\\s*jackson(?:\\s*\\.\\s*[\\w-]+)+\\s*:\\s*[\\w.-]+"
          + "|\\borg\\.springframework\\.boot\\s*:\\s*spring-boot-(?:starter-)?jackson\\b"
          + "|(?m)^\\s*(?!exclude\\b)\\w+\\s*(?:\\(\\s*)?"
          + "group\\s*[:=]\\s*['\"]org\\.springframework\\.boot['\"]"
          + "[^\\r\\n]{0,200}?\\b(?:name|module)\\s*[:=]\\s*['\"]spring-boot-(?:starter-)?jackson['\"]"
          + "|(?m)^\\s*(?!exclude\\b)\\w+\\s*(?:\\(\\s*)?"
          + "(?:name|module)\\s*[:=]\\s*['\"]spring-boot-(?:starter-)?jackson['\"]"
          + "[^\\r\\n]{0,200}?\\bgroup\\s*[:=]\\s*['\"]org\\.springframework\\.boot['\"]");
  private static final Pattern JACKSON3_SPRING = Pattern.compile(
      "\\borg\\.springframework\\.boot\\.jackson(?!2\\b)(?:\\.\\w+)+\\b"
          + "|\\b(?:JacksonJson\\w*|JsonMapperBuilderCustomizer)\\b");
  private static final Pattern JACKSON3_CONFIGURATION = Pattern.compile(
      "(?i)preferred[-_.]?json[-_.]?mapper\\s*['\"]?\\s*[,:=]\\s*['\"]?jackson(?:3)?\\b"
          + "|\\bspring[._-]jackson(?!2)(?:\\b|_)"
          + "|(?m)^\\s*jackson3\\s*:");
  private static final Pattern JACKSONIZED = Pattern.compile("@(?:lombok\\.extern\\.jackson\\.)?Jacksonized\\b");
  private static final Pattern QUOTED_OR_COMMENT = Pattern.compile(
      "(?s)(\"\"\".*?\"\"\"|'''.*?'''|\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+')"
          + "|(//[^\\r\\n]*|/\\*.*?(?:\\*/|\\z)|<!--.*?(?:-->|\\z))");
  private static final Pattern QUOTED_OR_HASH_COMMENT = Pattern.compile(
      "(\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+')|(#[^\\r\\n]*)");
  private static final Pattern YAML_MAPPING = Pattern.compile(
      "^([ \\t]*)([^\\s#'\"\\-&*!|>\\[{][^:]*?|'[^']*'|\"[^\"]*\")[ \\t]*:(?=[ \\t]|$)([^\\r\\n]*)$");
  private static final String LOMBOK_KEY = "lombok.jacksonized.jacksonVersion";

  public JacksonCompatibilityCheck() {
    getReportOnly().convention(false);
  }

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getScanFiles();

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getLombokConfigs();

  @Input
  public abstract Property<Boolean> getReportOnly();

  @Input
  public abstract org.gradle.api.provider.ListProperty<String> getAllowedSourceFiles();

  @Internal
  public abstract DirectoryProperty getProjectDirectory();

  @OutputFile
  public abstract RegularFileProperty getReportFile();

  @Option(option = "report-only",
      description = "Write findings without failing this invocation (migration inventory only)")
  public void reportOnly(boolean reportOnly) {
    getReportOnly().set(reportOnly);
  }

  static FileTree sourceTree(Project project, File directory) {
    return project.fileTree(directory, tree -> {
      tree.include("**/*.java", "**/*.kt", "**/*.kts", "**/*.groovy", "**/*.scala", "**/*.gradle",
          "**/*.yaml", "**/*.yml", "**/*.properties", "**/*.xml", "**/*.toml", "**/*.json", "**/lombok.config");
      tree.exclude("**/.git/**", "**/.gradle/**", "**/.idea/**", "**/build/**", "**/out/**",
          "**/target/**", "**/node_modules/**", "**/.yarn/**", "**/.venv/**", "**/vendor/**");
      Path root = directory.toPath().toAbsolutePath().normalize();
      Path build = project.getLayout().getBuildDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
      if (build.startsWith(root)) {
        tree.exclude(root.relativize(build).toString().replace(File.separatorChar, '/') + "/**");
      }
    });
  }

  static List<File> ancestorConfigs(File directory) {
    List<File> configs = new ArrayList<>();
    for (File dir = directory.getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
      configs.add(new File(dir, "lombok.config"));
    }
    return configs;
  }

  @TaskAction
  public void verify() throws IOException {
    Set<String> findings = new TreeSet<>();
    List<File> files = getScanFiles().getFiles().stream().filter(File::isFile).toList();
    for (File file : files) {
      scan(file, findings);
    }
    Set<String> allowedFiles = Set.copyOf(getAllowedSourceFiles().get());
    List<String> allowed = findings.stream().filter(finding -> isAllowed(finding, allowedFiles)).toList();
    List<String> enforced = findings.stream().filter(finding -> !isAllowed(finding, allowedFiles)).toList();
    String report = "Jackson 2 compatibility guard: " + files.size() + " files, " + enforced.size()
        + " enforced findings, " + allowed.size() + " allowed findings\n"
        + "Jackson 2 com.fasterxml.jackson APIs are allowed; tools.jackson APIs are forbidden.\n"
        + "Scope: source, mapper configuration and Lombok Jacksonized configuration; "
        + "dependency bytecode and generated code are not scanned.\n"
        + "This scan does not prove that stored case JSON survives a round trip.\n\n"
        + findings.stream()
            .map(finding -> (isAllowed(finding, allowedFiles) ? "ALLOWED " : "ERROR ") + finding)
            .collect(java.util.stream.Collectors.joining("\n")) + "\n";
    Path output = getReportFile().get().getAsFile().toPath();
    Files.createDirectories(output.getParent());
    Files.writeString(output, report, StandardCharsets.UTF_8);
    getLogger().lifecycle("Jackson compatibility: {} enforced, {} allowed findings. Report: {}",
        enforced.size(), allowed.size(), output);
    if (!enforced.isEmpty()) {
      enforced.forEach(finding -> getLogger().warn("WARNING: {}", finding));
      if (!getReportOnly().get()) {
        throw new GradleException("Jackson 2 compatibility guard failed with " + enforced.size()
            + " findings. See " + output + ". Remove Jackson 3 usage before releasing.");
      }
    }
  }

  private static boolean isAllowed(String finding, Set<String> allowedFiles) {
    return allowedFiles.stream().anyMatch(file -> finding.startsWith(file + ":"));
  }

  /**
   * Scans one source or resource file.
   */
  private void scan(File file, Set<String> findings) throws IOException {
    String name = file.getName();
    String text = withoutComments(Files.readString(file.toPath(), StandardCharsets.UTF_8), name);
    String code = withoutQuotedLiterals(text);
    final String configuration = isYaml(name) ? flattenYaml(text) : text;
    match(file, code, JACKSON3_API, "JACKSON3_API", "use the Jackson 2 com.fasterxml.jackson API", findings);
    match(file, text, JACKSON3_COORDINATE, "JACKSON3_API",
        "use the Jackson 2 com.fasterxml.jackson dependency", findings);
    match(file, code, JACKSON3_SPRING, "JACKSON3_SPRING", "use Spring's Jackson 2 integration",
        findings);
    match(file, configuration, JACKSON3_CONFIGURATION, "JACKSON3_CONFIG",
        "select Jackson 2 configuration", findings);
    if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".groovy")) {
      var matcher = JACKSONIZED.matcher(text);
      if (matcher.find() && usesJacksonThree(file)) {
        add(file, text, matcher.start(), "JACKSONIZED_CONFIG",
            "@Jacksonized is configured to generate Jackson 3 metadata; remove jacksonVersion 3", findings);
      }
    }
  }

  private boolean usesJacksonThree(File source) throws IOException {
    List<String> instructions = new ArrayList<>();
    List<File> configs = ancestorConfigs(source.getParentFile());
    for (File config : configs) {
      if (config.isFile()) {
        String text = withoutComments(Files.readString(config.toPath(), StandardCharsets.UTF_8), "lombok.config");
        instructions.add(text);
        if (Pattern.compile("(?m)^\\s*config\\.stopBubbling\\s*=\\s*true\\s*$").matcher(text).find()) {
          break;
        }
      }
    }
    Collections.reverse(instructions);
    Set<String> versions = new HashSet<>();
    for (String instruction : instructions) {
      for (String line : instruction.lines().map(String::strip).toList()) {
        if (line.startsWith("import ")) {
          return false;
        }
        if (line.matches("clear\\s+" + Pattern.quote(LOMBOK_KEY))) {
          versions.clear();
        } else if (line.startsWith(LOMBOK_KEY)) {
          String operation = line.substring(LOMBOK_KEY.length()).strip();
          if (operation.startsWith("+=")) {
            versions.add(operation.substring(2).strip());
          } else if (operation.startsWith("-=")) {
            versions.remove(operation.substring(2).strip());
          }
        }
      }
    }
    return versions.contains("3");
  }

  private void match(File file, String text, Pattern pattern, String code, String advice, Set<String> findings) {
    var matcher = pattern.matcher(text);
    while (matcher.find()) {
      add(file, text, matcher.start(), code, matcher.group().replaceAll("\\s+", "") + " — " + advice, findings);
    }
  }

  private void add(File file, String text, int offset, String code, String message, Set<String> findings) {
    Path root = getProjectDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
    Path path = file.toPath().toAbsolutePath().normalize();
    String relative = (path.startsWith(root) ? root.relativize(path) : path).toString()
        .replace(File.separatorChar, '/');
    int line = (int) text.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
    findings.add(relative + ":" + line + " [" + code + "] " + message);
  }

  private static boolean isYaml(String name) {
    return name.endsWith(".yaml") || name.endsWith(".yml");
  }

  /**
   * Rewrites each YAML mapping line as its dotted key path so flat property patterns apply to nested
   * configuration. Line numbers are preserved; sequences and scalars are left as they are.
   */
  static String flattenYaml(String text) {
    Deque<int[]> indents = new ArrayDeque<>();
    Deque<String> keys = new ArrayDeque<>();
    StringBuilder result = new StringBuilder(text.length());
    String[] lines = text.split("\n", -1);
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index];
      String trimmed = line.strip();
      if (trimmed.startsWith("---")) {
        indents.clear();
        keys.clear();
      }
      Matcher mapping = YAML_MAPPING.matcher(line.replace("\r", ""));
      if (mapping.matches()) {
        int indent = mapping.group(1).length();
        while (!indents.isEmpty() && indents.peek()[0] >= indent) {
          indents.pop();
          keys.pop();
        }
        String key = mapping.group(2).strip();
        if (key.length() > 1 && (key.startsWith("'") || key.startsWith("\""))) {
          key = key.substring(1, key.length() - 1);
        }
        List<String> path = new ArrayList<>(keys);
        Collections.reverse(path);
        path.add(key);
        indents.push(new int[] {indent});
        keys.push(key);
        line = String.join(".", path) + ":" + mapping.group(3);
      }
      result.append(line);
      if (index < lines.length - 1) {
        result.append('\n');
      }
    }
    return result.toString();
  }

  private static String withoutComments(String text, String name) {
    String result = maskComments(text, QUOTED_OR_COMMENT);
    if (isYaml(name) || name.endsWith(".properties") || name.endsWith(".toml") || name.equals("lombok.config")) {
      result = maskComments(result, QUOTED_OR_HASH_COMMENT);
    }
    return result;
  }

  private static String maskComments(String text, Pattern pattern) {
    return pattern.matcher(text).replaceAll(match -> Matcher.quoteReplacement(
        match.group(1) != null ? match.group() : match.group().replaceAll("[^\\r\\n]", " ")));
  }

  private static String withoutQuotedLiterals(String text) {
    return QUOTED_OR_COMMENT.matcher(text).replaceAll(match -> Matcher.quoteReplacement(
        match.group().replaceAll("[^\\r\\n]", " ")));
  }
}
