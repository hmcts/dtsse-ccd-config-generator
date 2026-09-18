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
import org.gradle.api.provider.ListProperty;
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

/** Checks migration hazards without loading application classes or resolving dependencies. */
@DisableCachingByDefault(because = "Verification reports are inexpensive and contain project-relative diagnostics")
public abstract class JacksonCompatibilityCheck extends DefaultTask {
  private static final String JACKSON = "com\\s*\\.\\s*fasterxml\\s*\\.\\s*jackson\\s*\\.\\s*";
  private static final Pattern LEGACY_API = Pattern.compile("\\b" + JACKSON
      + "(?!annotation\\b)(?:\\w+\\s*\\.\\s*)+[\\w$*]+|\\b" + JACKSON + "\\*");
  private static final Pattern LEGACY_SPRING = Pattern.compile(
      "\\b(?:MappingJackson2\\w*|Jackson2\\w*|JsonComponent\\w*|JsonMixin\\w*"
          + "|JsonObject(?:Serializer|Deserializer)|JsonValue(?:Serializer|Deserializer)"
          + "|\\w+Jackson2(?:HttpMessageConverter|MessageConverter|JsonEncoder|JsonDecoder|Tokenizer|CodecSupport"
          + "|ObjectMapperBuilder\\w*|JsonView))\\b"
          + "|\\b(?:org\\.springframework\\.)?boot\\.jackson2\\b");
  private static final Pattern LEGACY_CONFIGURATION = Pattern.compile(
      "(?i)preferred[-_.]?json[-_.]?mapper\\s*['\"]?\\s*[,:=]\\s*['\"]?jackson2\\b"
          + "|\\bspring[._-]jackson2(?:\\b|_)"
          + "|\\bspring[._-]jackson[._-](?:read|write|parser|generator)(?:\\b|_)"
          + "|(?m)^\\s*jackson2\\s*:");
  private static final Pattern JACKSONIZED = Pattern.compile("@(?:lombok\\.extern\\.jackson\\.)?Jacksonized\\b");
  private static final int[] LOMBOK_JACKSON3_SUPPORT = {1, 18, 44};
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

  /**
   * Declared Lombok annotation processors as {@code configurationName=version}, taken from each source set's
   * annotation processor configuration without resolving it.
   */
  @Input
  public abstract ListProperty<String> getLombokProcessors();

  @Input
  public abstract Property<Boolean> getReportOnly();

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
    boolean jacksonized = false;
    for (File file : files) {
      jacksonized |= scan(file, findings);
    }
    if (jacksonized) {
      checkLombokVersions(findings);
    }
    String report = "Jackson 3 compatibility guard: " + files.size() + " files, " + findings.size() + " findings\n"
        + "Shared com.fasterxml.jackson.annotation APIs and jackson-annotations are allowed.\n"
        + "Jackson 2 dependencies may coexist, but project sources must not use their removed APIs.\n"
        + "Scope: source, mapper configuration and declared Lombok processors; "
        + "dependency bytecode and generated code are not scanned.\n"
        + "This scan does not prove that stored case JSON survives a round trip.\n\n"
        + String.join("\n", findings) + "\n";
    Path output = getReportFile().get().getAsFile().toPath();
    Files.createDirectories(output.getParent());
    Files.writeString(output, report, StandardCharsets.UTF_8);
    getLogger().lifecycle("Jackson compatibility: {} findings. Report: {}", findings.size(), output);
    if (!findings.isEmpty()) {
      findings.forEach(finding -> getLogger().warn("WARNING: {}", finding));
      if (!getReportOnly().get()) {
        throw new GradleException("Jackson 3 compatibility guard failed with " + findings.size()
            + " findings. See " + output + ". Migrate Jackson 2 usage before releasing.");
      }
    }
  }

  /**
   * Scans one source or resource file; returns whether it uses {@code @Jacksonized}.
   */
  private boolean scan(File file, Set<String> findings) throws IOException {
    String name = file.getName();
    String text = withoutComments(Files.readString(file.toPath(), StandardCharsets.UTF_8), name);
    String configuration = isYaml(name) ? flattenYaml(text) : text;
    match(file, text, LEGACY_API, "JACKSON2_API", "migrate to the Jackson 3 tools.jackson API", findings);
    match(file, text, LEGACY_SPRING, "JACKSON2_SPRING", "remove Jackson 2 integration or use its Jackson 3 equivalent",
        findings);
    match(file, configuration, LEGACY_CONFIGURATION, "JACKSON2_CONFIG",
        "use Jackson 3 Spring Boot configuration", findings);
    boolean jacksonized = false;
    if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".groovy")) {
      var matcher = JACKSONIZED.matcher(text);
      jacksonized = matcher.find();
      if (jacksonized && !usesJacksonThreeOnly(file)) {
        add(file, text, matcher.start(), "JACKSONIZED_CONFIG",
            "@Jacksonized requires effective lombok.jacksonized.jacksonVersion += 3, without version 2; "
                + "imported Lombok configurations require manual migration to a locally verifiable setting", findings);
      }
    }
    return jacksonized;
  }

  private void checkLombokVersions(Set<String> findings) {
    for (String processor : getLombokProcessors().get()) {
      String[] parts = processor.split("=", 2);
      Matcher version = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(parts[1]);
      if (version.lookingAt() && compare(version(version), LOMBOK_JACKSON3_SUPPORT) < 0) {
        findings.add(parts[0] + " [JACKSONIZED_LOMBOK] org.projectlombok:lombok:" + parts[1]
            + " — @Jacksonized emits the Jackson 2 @JsonPOJOBuilder before Lombok "
            + LOMBOK_JACKSON3_SUPPORT[0] + "." + LOMBOK_JACKSON3_SUPPORT[1] + "." + LOMBOK_JACKSON3_SUPPORT[2]
            + " whatever lombok.jacksonized.jacksonVersion says, so Jackson 3 silently ignores every builder property; "
            + "upgrade Lombok");
      }
    }
  }

  private boolean usesJacksonThreeOnly(File source) throws IOException {
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
    return versions.equals(Set.of("3"));
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

  private static int[] version(Matcher matcher) {
    return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
        Integer.parseInt(matcher.group(3))};
  }

  private static int compare(int[] left, int[] right) {
    for (int index = 0; index < left.length; index++) {
      int difference = Integer.compare(left[index], right[index]);
      if (difference != 0) {
        return difference;
      }
    }
    return 0;
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
}
