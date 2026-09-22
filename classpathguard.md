# Jackson 2 classpath compatibility guard

## Purpose

The generator and its consumers use Jackson 2.21.5. Consumer application and
HMCTS-owned bytecode must not use Jackson 3 APIs. Jackson 3 may still be present on the
runtime classpath because Spring Boot and other third-party libraries use it internally.

Two verification tasks enforce that rule:

```text
jackson2CompatibilityGuard
jackson2ClasspathGuard
```

`check` depends on both tasks.

The source guard provides fast feedback without compiling or resolving dependencies.
The classpath guard is authoritative: it inspects compiled application classes and all
resolved runtime dependencies, including transitive dependencies and generated
bytecode.

## Source guard

`jackson2CompatibilityGuard` rejects:

- references under `tools.jackson.*`;
- Spring's Jackson 3 integration and Jackson 3 mapper selection;
- Lombok `@Jacksonized` classes whose effective configuration includes
  `lombok.jacksonized.jacksonVersion += 3`.

Jackson 2 APIs under `com.fasterxml.jackson.*`, including databind annotations, are
allowed.

The task writes:

```text
build/reports/jackson-compatibility/report.txt
```

`--report-only` writes the inventory without failing that invocation. It does not
change the behaviour of a later `check` invocation.

## Classpath guard

`jackson2ClasspathGuard` uses ASM and never loads scanned classes. It scans:

- every registered source set's compiled class directories;
- Gradle project dependencies;
- first-party and third-party dependency JARs;
- runtime-visible and runtime-invisible annotations;
- superclasses, interfaces, descriptors, generic signatures, annotation values and
  typed bytecode instructions;
- the Java-toolchain-selected entry in multi-release JARs.

It reports:

```text
JACKSON3_ARTIFACT
JACKSON3_DATABIND_ANNOTATION
JACKSON3_API_REFERENCE
```

Official `tools.jackson.*` artifacts produce one artifact-level finding instead of a
finding for every class inside the library. Other libraries are scanned for compiled
references to `tools.jackson.*`.

Findings in project output, Gradle project dependencies and configured first-party
groups are fatal. Findings in third-party dependencies—including the Jackson 3
artifacts used internally by Spring Boot—are informational. This allows framework
internals while preventing consumer applications and shared HMCTS libraries from
coupling to Jackson 3.

Reviewed exceptions may be configured only as exact source files or exact
component/class pairs. Allowed findings remain visible in both reports and summaries.

The task writes:

```text
build/reports/jackson-compatibility/classpath-report.txt
```

Each finding identifies its scope, component, class, member when available, finding
type, context and referenced Jackson 3 type.

## Gradle configuration

Project dependencies and these groups are labelled first-party in diagnostics by
default:

```text
com.github.hmcts
uk.gov.hmcts
```

Additional first-party groups can be configured for clearer ownership reporting:

```groovy
ccdSdk {
  jackson2ClasspathGuard {
    firstPartyGroups = ['com.github.hmcts', 'uk.gov.hmcts', 'org.example']
    allowedSourceFiles = [
      'src/main/java/org/example/ExistingBridge.java'
    ]
    allowedClasses = [
      'project(:)|org.example.ExistingBridge'
    ]
    allowedComponents = [
      'com.github.hmcts:existing-jackson3-client:1.2.3'
    ]
  }
}
```

Classification controls enforcement: project and first-party findings fail;
third-party findings remain visible without failing.

Allowed source paths are relative to the project directory. Allowed bytecode entries
use either the exact `component|fully.qualified.ClassName` or the exact versioned
component shown in the report. Component exceptions keep every finding visible and
force re-review when the dependency version changes. Wildcards and package-level
exclusions are not supported. Nested classes must be listed separately.

## Current reviewed exceptions

- The e2e fixture uses `core-case-data-store-client:6.1.0`, whose public Spring configuration and
  model metadata use Jackson 3. The fixture supplies one exact Jackson 3 mapper bridge and permits
  only its build file, bridge class and that exact client version. The application's primary HTTP
  mapper and CCD wire contracts remain on Jackson 2.
- Adoption allows only
  `com.github.hmcts:core-case-data-store-client:6.1.0`. The dependency's Jackson 3 findings remain
  visible, and any version change requires review.
- NFDiv, PCS and SPTribs have no Jackson 3 exceptions. Their application mappers, round-trip
  tests and casing/null configuration remain on Jackson 2.

## Failure behaviour

Unreadable classes or JARs fail explicitly with the component and entry. The scanner
does not silently skip malformed artifacts.

The task is cacheable. Its inputs include compiled classes, resolved artifacts,
component metadata, first-party group configuration, scanner version and active Java
toolchain version.

## Acceptance criteria

1. Jackson 2.21.5 artifacts and `com.fasterxml.jackson.*` bytecode pass.
2. Direct or transitive third-party `tools.jackson.*` artifacts are reported but do
   not fail solely because they are present.
3. Project or dependency bytecode referencing Jackson 3 fails.
4. Generated Jackson 3 annotations fail.
5. Third-party Jackson 3 usage is informational, while project and first-party usage
   fails.
6. The scanner does not load application or dependency classes.
7. Multi-release JARs and malformed artifacts are handled deterministically.
8. Reports are stable, cacheable and identify the owning component.
