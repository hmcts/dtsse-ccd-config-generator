package uk.gov.hmcts.ccd.sdk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

/** Scans compiled project and dependency bytecode for forbidden Jackson 3 metadata and API references. */
@CacheableTask
public abstract class JacksonClasspathCompatibilityCheck extends DefaultTask {
  private static final String SCANNER_VERSION = "4";
  private static final String JACKSON3_ANNOTATION = "tools/jackson/databind/annotation/";
  private static final String JACKSON3_API_PREFIX = "tools/jackson/";
  private static final Pattern SIGNATURE_TYPE = Pattern.compile("L([^;<:]+)");

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getProjectClasses();

  @Classpath
  public abstract ConfigurableFileCollection getDependencyArtifacts();

  /** Stable component metadata excluding absolute paths. */
  @Input
  public abstract ListProperty<String> getArtifactMetadata();

  /** Execution-only lookup from an artifact's absolute path to its tab-separated metadata. */
  @Internal
  public abstract MapProperty<String, String> getArtifactMetadataByPath();

  @Input
  public abstract ListProperty<String> getFirstPartyGroups();

  @Input
  public abstract ListProperty<String> getAllowedClasses();

  @Input
  public abstract ListProperty<String> getAllowedComponents();

  @Input
  public abstract Property<Integer> getJavaVersion();

  @Input
  public String getScannerVersion() {
    return SCANNER_VERSION;
  }

  @Input
  public abstract Property<String> getProjectComponent();

  @OutputFile
  public abstract RegularFileProperty getReportFile();

  @TaskAction
  public void verify() {
    ScanTotals totals = new ScanTotals();
    Set<Finding> findings = new TreeSet<>();
    try {
      scanProjectOutput(findings, totals);
      scanDependencies(findings, totals);
    } catch (Exception exception) {
      writeFailureReport(exception, totals, findings);
      if (exception instanceof GradleException gradleException) {
        throw gradleException;
      }
      throw new GradleException("Jackson 2 classpath guard could not scan bytecode: "
          + exception.getMessage(), exception);
    }

    Set<String> allowedClasses = Set.copyOf(getAllowedClasses().get());
    Set<String> allowedComponents = Set.copyOf(getAllowedComponents().get());
    long allowedCount = findings.stream()
        .filter(finding -> isAllowed(finding, allowedClasses, allowedComponents)).count();
    long enforcedCount = findings.stream()
        .filter(finding -> finding.scope.enforced && !isAllowed(finding, allowedClasses, allowedComponents)).count();
    long informationalCount = findings.size() - enforcedCount - allowedCount;
    writeReport(totals, findings.stream().toList(), enforcedCount, informationalCount, allowedCount, null);

    Path report = getReportFile().get().getAsFile().toPath();
    getLogger().lifecycle(
        "Jackson 2 classpath compatibility: {} enforced, {} informational, {} allowed Jackson 3 findings. Report: {}",
        enforcedCount, informationalCount, allowedCount, report);
    if (enforcedCount > 0) {
      throw new GradleException("Jackson 2 classpath guard failed with " + enforcedCount
          + " first-party Jackson 3 findings. Consumer application code must use Jackson 2. See " + report + ".");
    }
  }

  private void scanProjectOutput(Set<Finding> findings, ScanTotals totals) throws IOException {
    List<File> roots = getProjectClasses().getFiles().stream()
        .filter(File::exists).sorted(Comparator.comparing(File::getAbsolutePath)).toList();
    for (File root : roots) {
      if (root.isDirectory()) {
        try (var paths = Files.walk(root.toPath())) {
          for (Path path : paths.filter(Files::isRegularFile)
              .filter(file -> file.toString().endsWith(".class")).sorted().toList()) {
            scanClass(Files.readAllBytes(path), Scope.PROJECT_OUTPUT, getProjectComponent().get(),
                root.toPath().relativize(path).toString(), findings);
            totals.projectClasses++;
          }
        }
      } else if (root.getName().endsWith(".class")) {
        scanClass(Files.readAllBytes(root.toPath()), Scope.PROJECT_OUTPUT, getProjectComponent().get(),
            root.getName(), findings);
        totals.projectClasses++;
      }
    }
  }

  private void scanDependencies(Set<Finding> findings, ScanTotals totals) throws IOException {
    Map<String, String> metadata = getArtifactMetadataByPath().get();
    Set<String> scanned = new HashSet<>();
    for (File artifact : getDependencyArtifacts().getFiles().stream()
        .sorted(Comparator.comparing(File::getAbsolutePath)).toList()) {
      String key = artifact.getAbsoluteFile().toPath().normalize().toString();
      if (!scanned.add(key)) {
        continue;
      }
      String descriptor = metadata.get(key);
      if (descriptor == null) {
        throw new GradleException("Missing component metadata for classpath artifact " + artifact);
      }
      String[] parts = descriptor.split("\\t", -1);
      boolean projectDependency = parts[0].equals("PROJECT");
      String component = parts[1];
      String group = parts[2];
      Scope scope = projectDependency || getFirstPartyGroups().get().contains(group)
          ? Scope.FIRST_PARTY_DEPENDENCY : Scope.THIRD_PARTY_DEPENDENCY;
      if (group.startsWith("tools.jackson")) {
        findings.add(new Finding(scope, component, "<artifact>", "", FindingType.JACKSON3_ARTIFACT,
            component, "dependency artifact"));
        totals.dependencyArtifacts++;
      } else if (artifact.isDirectory()) {
        scanDependencyDirectory(artifact, scope, component, findings, totals);
      } else {
        scanJar(artifact, scope, component, findings, totals);
        totals.dependencyArtifacts++;
      }
    }
  }

  private void scanDependencyDirectory(File root, Scope scope, String component, Set<Finding> findings,
                                       ScanTotals totals) throws IOException {
    try (var paths = Files.walk(root.toPath())) {
      for (Path path : paths.filter(Files::isRegularFile)
          .filter(file -> file.toString().endsWith(".class")).sorted().toList()) {
        scanClass(Files.readAllBytes(path), scope, component, root.toPath().relativize(path).toString(), findings);
        totals.addDependencyClass(scope);
      }
    }
  }

  private void scanJar(File artifact, Scope scope, String component, Set<Finding> findings,
                       ScanTotals totals) throws IOException {
    try (JarFile jar = new JarFile(artifact)) {
      boolean multiRelease = jar.getManifest() != null
          && Boolean.parseBoolean(jar.getManifest().getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE));
      Map<String, VersionedEntry> selected = new HashMap<>();
      var entries = jar.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
          continue;
        }
        VersionedEntry candidate = VersionedEntry.of(entry);
        if (candidate.version > 0 && (!multiRelease || candidate.version > getJavaVersion().get())) {
          continue;
        }
        VersionedEntry current = selected.get(candidate.logicalName);
        if (current == null || candidate.version > current.version) {
          selected.put(candidate.logicalName, candidate);
        }
      }
      for (VersionedEntry entry : selected.values().stream()
          .sorted(Comparator.comparing(value -> value.logicalName)).toList()) {
        try (InputStream input = jar.getInputStream(entry.entry)) {
          scanClass(input.readAllBytes(), scope, component,
              artifact.getName() + "!/" + entry.entry.getName(), findings);
          totals.addDependencyClass(scope);
        } catch (RuntimeException | IOException exception) {
          throw new GradleException("Cannot scan " + component + " entry " + entry.entry.getName()
              + " in " + artifact + ": " + exception.getMessage(), exception);
        }
      }
    } catch (ZipException exception) {
      throw new GradleException("Cannot read classpath artifact " + component + " at " + artifact
          + ": " + exception.getMessage(), exception);
    }
  }

  private void scanClass(byte[] bytecode, Scope scope, String component, String entry,
                         Set<Finding> findings) {
    try {
      ClassReader reader = new ClassReader(bytecode);
      reader.accept(new Jackson3ReferenceVisitor(scope, component, entry, findings),
          ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    } catch (RuntimeException exception) {
      throw new GradleException("Cannot scan " + component + " entry " + entry + ": "
          + exception.getMessage(), exception);
    }
  }

  private void writeFailureReport(Exception failure, ScanTotals totals, Set<Finding> findings) {
    Set<String> allowedClasses = Set.copyOf(getAllowedClasses().get());
    Set<String> allowedComponents = Set.copyOf(getAllowedComponents().get());
    long allowed = findings.stream()
        .filter(finding -> isAllowed(finding, allowedClasses, allowedComponents)).count();
    long enforced = findings.stream()
        .filter(finding -> finding.scope.enforced && !isAllowed(finding, allowedClasses, allowedComponents)).count();
    writeReport(totals, findings.stream().toList(), enforced, findings.size() - enforced - allowed, allowed,
        "SCAN FAILURE: " + failure.getMessage());
  }

  private void writeReport(ScanTotals totals, List<Finding> findings, long enforced,
                           long informational, long allowed, String failure) {
    Set<String> allowedClasses = Set.copyOf(getAllowedClasses().get());
    Set<String> allowedComponents = Set.copyOf(getAllowedComponents().get());
    StringBuilder report = new StringBuilder();
    report.append("Jackson 2 classpath compatibility guard\n")
        .append("Project classes scanned: ").append(totals.projectClasses).append('\n')
        .append("Dependency artifacts scanned: ").append(totals.dependencyArtifacts).append('\n')
        .append("First-party dependency classes scanned: ").append(totals.firstPartyClasses).append('\n')
        .append("Third-party dependency classes scanned: ").append(totals.thirdPartyClasses).append('\n')
        .append("Enforced Jackson 3 findings: ").append(enforced).append('\n')
        .append("Informational Jackson 3 findings: ").append(informational).append('\n')
        .append("Allowed Jackson 3 findings: ").append(allowed).append('\n')
        .append("Project and first-party usage is forbidden; third-party runtime usage is informational.\n");
    if (failure != null) {
      report.append('\n').append(failure).append('\n');
    }
    for (Finding finding : findings.stream().sorted().toList()) {
      boolean allowedFinding = isAllowed(finding, allowedClasses, allowedComponents);
      report.append('\n').append(allowedFinding ? "ALLOWED" : finding.scope.enforced ? "ERROR" : "INFO")
          .append(" [").append(finding.scope).append("] ")
          .append(finding.component).append('\n')
          .append("  ").append(finding.className).append('\n');
      if (!finding.member.isEmpty()) {
        report.append("  ").append(finding.member).append('\n');
      }
      report.append("  ").append(finding.type).append('\n')
          .append("  ").append(finding.context).append(' ').append(finding.jacksonType).append('\n');
    }
    try {
      Path output = getReportFile().get().getAsFile().toPath();
      Files.createDirectories(output.getParent());
      Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new GradleException("Cannot write Jackson classpath report", exception);
    }
  }

  private static boolean isAllowed(Finding finding, Set<String> allowedClasses, Set<String> allowedComponents) {
    return allowedComponents.contains(finding.component)
        || allowedClasses.contains(finding.component + "|" + finding.className);
  }

  private enum Scope {
    PROJECT_OUTPUT(true), FIRST_PARTY_DEPENDENCY(true), THIRD_PARTY_DEPENDENCY(false);

    private final boolean enforced;

    Scope(boolean enforced) {
      this.enforced = enforced;
    }
  }

  private enum FindingType {
    JACKSON3_ARTIFACT, JACKSON3_DATABIND_ANNOTATION, JACKSON3_API_REFERENCE
  }

  private record Finding(Scope scope, String component, String className, String member,
                         FindingType type, String jacksonType, String context) implements Comparable<Finding> {
    @Override
    public int compareTo(Finding other) {
      return Comparator.comparing((Finding value) -> value.scope)
          .thenComparing(value -> value.component)
          .thenComparing(value -> value.className)
          .thenComparing(value -> value.member)
          .thenComparing(value -> value.type)
          .thenComparing(value -> value.jacksonType)
          .thenComparing(value -> value.context)
          .compare(this, other);
    }
  }

  private static class ScanTotals {
    private long projectClasses;
    private long dependencyArtifacts;
    private long firstPartyClasses;
    private long thirdPartyClasses;

    private void addDependencyClass(Scope scope) {
      if (scope == Scope.FIRST_PARTY_DEPENDENCY) {
        firstPartyClasses++;
      } else {
        thirdPartyClasses++;
      }
    }
  }

  private record VersionedEntry(String logicalName, int version, ZipEntry entry) {
    private static VersionedEntry of(ZipEntry entry) {
      String name = entry.getName();
      String prefix = "META-INF/versions/";
      if (!name.startsWith(prefix)) {
        return new VersionedEntry(name, 0, entry);
      }
      int slash = name.indexOf('/', prefix.length());
      if (slash < 0) {
        return new VersionedEntry(name, Integer.MAX_VALUE, entry);
      }
      try {
        return new VersionedEntry(name.substring(slash + 1),
            Integer.parseInt(name.substring(prefix.length(), slash)), entry);
      } catch (NumberFormatException exception) {
        return new VersionedEntry(name, Integer.MAX_VALUE, entry);
      }
    }
  }

  private static class Jackson3ReferenceVisitor extends ClassVisitor {
    private final Scope scope;
    private final String component;
    private final String entry;
    private final Set<Finding> findings;
    private String className = "<unknown>";

    private Jackson3ReferenceVisitor(Scope scope, String component, String entry, Set<Finding> findings) {
      super(Opcodes.ASM9);
      this.scope = scope;
      this.component = component;
      this.entry = entry;
      this.findings = findings;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
      className = dotted(name);
      type(superName, "", "superclass");
      if (interfaces != null) {
        Arrays.stream(interfaces).forEach(value -> type(value, "", "interface"));
      }
      signature(signature, "", "class signature");
    }

    @Override
    public void visitOuterClass(String owner, String name, String descriptor) {
      type(owner, "", "outer class");
      descriptor(descriptor, "", "outer method descriptor");
    }

    @Override
    public void visitNestHost(String nestHost) {
      type(nestHost, "", "nest host");
    }

    @Override
    public void visitNestMember(String nestMember) {
      type(nestMember, "", "nest member");
    }

    @Override
    public void visitPermittedSubclass(String permittedSubclass) {
      type(permittedSubclass, "", "permitted subclass");
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
      return annotation(descriptor, "",
          visible ? "runtime-visible class annotation" : "runtime-invisible class annotation");
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
      return annotation(descriptor, "", visible ? "runtime-visible class type annotation"
          : "runtime-invisible class type annotation");
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
      String member = "field " + name;
      descriptor(descriptor, member, "field descriptor");
      signature(signature, member, "field signature");
      value(value, member, "field constant");
      return new FieldVisitor(Opcodes.ASM9) {
        @Override
        public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
          return annotation(annotationDescriptor, member,
              visible ? "runtime-visible field annotation" : "runtime-invisible field annotation");
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                     String annotationDescriptor, boolean visible) {
          return annotation(annotationDescriptor, member,
              visible ? "runtime-visible field type annotation" : "runtime-invisible field type annotation");
        }
      };
    }

    @Override
    public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
      String member = "record component " + name;
      descriptor(descriptor, member, "record component descriptor");
      signature(signature, member, "record component signature");
      return new RecordComponentVisitor(Opcodes.ASM9) {
        @Override
        public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
          return annotation(annotationDescriptor, member,
              visible ? "runtime-visible record annotation" : "runtime-invisible record annotation");
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                     String annotationDescriptor, boolean visible) {
          return annotation(annotationDescriptor, member,
              visible ? "runtime-visible record type annotation" : "runtime-invisible record type annotation");
        }
      };
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                     String[] exceptions) {
      String member = (name.equals("<init>") ? "constructor " : "method ") + name + descriptor;
      descriptor(descriptor, member, "method descriptor");
      signature(signature, member, "method signature");
      if (exceptions != null) {
        Arrays.stream(exceptions).forEach(value -> type(value, member, "declared exception"));
      }
      return new MethodVisitor(Opcodes.ASM9) {
        @Override
        public AnnotationVisitor visitAnnotationDefault() {
          return values(member, "annotation default");
        }

        @Override
        public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
          return annotation(annotationDescriptor, member,
              visible ? "runtime-visible method annotation" : "runtime-invisible method annotation");
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                     String annotationDescriptor, boolean visible) {
          return annotation(annotationDescriptor, member,
              visible ? "runtime-visible method type annotation" : "runtime-invisible method type annotation");
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String annotationDescriptor, boolean visible) {
          return annotation(annotationDescriptor, member + " parameter " + parameter,
              visible ? "runtime-visible parameter annotation" : "runtime-invisible parameter annotation");
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
          type(type, member, "bytecode type");
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String fieldDescriptor) {
          type(owner, member, "field instruction owner");
          descriptor(fieldDescriptor, member, "field instruction descriptor");
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String methodDescriptor,
                                    boolean isInterface) {
          type(owner, member, "method instruction owner");
          descriptor(methodDescriptor, member, "method instruction descriptor");
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String dynamicDescriptor, Handle bootstrapMethodHandle,
                                           Object... bootstrapMethodArguments) {
          descriptor(dynamicDescriptor, member, "invokedynamic descriptor");
          value(bootstrapMethodHandle, member, "bootstrap method");
          Arrays.stream(bootstrapMethodArguments).forEach(argument -> value(argument, member, "bootstrap argument"));
        }

        @Override
        public void visitLdcInsn(Object constant) {
          value(constant, member, "typed constant");
        }

        @Override
        public void visitMultiANewArrayInsn(String arrayDescriptor, int dimensions) {
          descriptor(arrayDescriptor, member, "array descriptor");
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
          type(type, member, "catch type");
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath,
                                                         String annotationDescriptor, boolean visible) {
          return annotation(annotationDescriptor, member,
              visible ? "runtime-visible catch annotation" : "runtime-invisible catch annotation");
        }

        @Override
        public void visitLocalVariable(String name, String localDescriptor, String localSignature,
                                       Label start, Label end, int index) {
          descriptor(localDescriptor, member, "local variable descriptor");
          signature(localSignature, member, "local variable signature");
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start,
                                                              Label[] end, int[] index, String annotationDescriptor,
                                                              boolean visible) {
          return annotation(annotationDescriptor, member,
              visible ? "runtime-visible local annotation" : "runtime-invisible local annotation");
        }
      };
    }

    private AnnotationVisitor annotation(String descriptor, String member, String context) {
      String internalName = Type.getType(descriptor).getInternalName();
      if (internalName.startsWith(JACKSON3_ANNOTATION)) {
        add(member, FindingType.JACKSON3_DATABIND_ANNOTATION, internalName, context);
      } else {
        type(internalName, member, context);
      }
      return values(member, context + " value");
    }

    private AnnotationVisitor values(String member, String context) {
      return new AnnotationVisitor(Opcodes.ASM9) {
        @Override
        public void visit(String name, Object annotationValue) {
          value(annotationValue, member, context);
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
          descriptor(descriptor, member, context + " enum");
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
          return annotation(descriptor, member, context + " annotation");
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
          return values(member, context + " array");
        }
      };
    }

    private void value(Object value, String member, String context) {
      if (value instanceof Type type) {
        asmType(type, member, context);
      } else if (value instanceof Handle handle) {
        type(handle.getOwner(), member, context + " owner");
        descriptor(handle.getDesc(), member, context + " descriptor");
      } else if (value instanceof ConstantDynamic dynamic) {
        descriptor(dynamic.getDescriptor(), member, context + " descriptor");
        value(dynamic.getBootstrapMethod(), member, context + " bootstrap method");
        for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
          value(dynamic.getBootstrapMethodArgument(index), member, context + " bootstrap argument");
        }
      }
    }

    private void descriptor(String descriptor, String member, String context) {
      if (descriptor == null) {
        return;
      }
      try {
        asmType(Type.getType(descriptor), member, context);
      } catch (IllegalArgumentException exception) {
        throw new GradleException("Invalid descriptor in " + entry + ": " + descriptor, exception);
      }
    }

    private void asmType(Type type, String member, String context) {
      if (type.getSort() == Type.METHOD) {
        Arrays.stream(type.getArgumentTypes()).forEach(value -> asmType(value, member, context));
        asmType(type.getReturnType(), member, context);
      } else if (type.getSort() == Type.ARRAY) {
        asmType(type.getElementType(), member, context);
      } else if (type.getSort() == Type.OBJECT) {
        type(type.getInternalName(), member, context);
      }
    }

    private void signature(String signature, String member, String context) {
      if (signature == null) {
        return;
      }
      Matcher matcher = SIGNATURE_TYPE.matcher(signature);
      while (matcher.find()) {
        type(matcher.group(1), member, context);
      }
    }

    private void type(String internalName, String member, String context) {
      if (internalName == null) {
        return;
      }
      if (internalName.startsWith(JACKSON3_API_PREFIX)) {
        add(member, FindingType.JACKSON3_API_REFERENCE, internalName, context);
      }
    }

    private void add(String member, FindingType type, String internalName, String context) {
      findings.add(new Finding(scope, component, className, member, type, dotted(internalName), context));
    }

    private static String dotted(String internalName) {
      return internalName.replace('/', '.');
    }
  }
}
