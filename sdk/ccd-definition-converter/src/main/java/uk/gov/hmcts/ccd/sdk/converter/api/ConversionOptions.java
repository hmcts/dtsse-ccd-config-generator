package uk.gov.hmcts.ccd.sdk.converter.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;

/** The parsed, validated converter configuration; built by the CLI, consumed everywhere. */
@Value
@Builder(toBuilder = true)
public class ConversionOptions {

  /** Input sheet directories (files or per-sheet fragment directories inside). */
  List<Path> inputs;

  /** Case type filter; null converts the only case type present (error if ambiguous). */
  String caseTypeId;

  /** Root directory generated Java sources are written under. */
  Path outputSrc;

  /** Package for CaseData, complex types and enums. Must live under uk.gov.hmcts. */
  String modelPackage;

  /** Package for config and event classes. Must live under uk.gov.hmcts. */
  String configPackage;

  /** Overlay filename suffix to environment predicate, e.g. "prod" -> CCD_DEF_ENV:prod. */
  Map<String, OverlayCondition> overlaySuffixes;

  /** Where raw-JSON passthrough content is written. */
  Path passthroughDir;

  /** Where gap-report.json / gap-report.md are written. */
  Path reportDir;

  /** Events per generated CCDConfig class (method-per-event within each). */
  int eventsPerConfig;

  /**
   * Emit a minimal @SpringBootApplication for running the generator standalone. Not for output
   * destined for a service's source tree — a service's own @SpringBootApplication already
   * discovers the generated CCDConfig beans via its component scan, and generateCCDConfig requires
   * exactly one @SpringBootApplication under ccd.rootPackage. Used only by ad-hoc standalone runs
   * and internal test harnesses (round-trip tests that compile generated sources in isolation).
   */
  boolean emitApplication;

  /** Complete with OMITTED_FAIL gaps instead of failing the conversion. */
  boolean allowGaps;

  /**
   * Retrofit mode: emit companion config/enum/access sources targeting the team's EXISTING model
   * (via {@link #retrofitCaseDataClass} etc.) instead of a fresh generated {@code CaseData}. When
   * set the linker skips {@code FieldClusterer} (a real model already carries its own
   * {@code @JsonUnwrapped} structure, so no synthetic clustering is folded in), and the model
   * emitters (CaseData/complex types) are dropped by {@link uk.gov.hmcts.ccd.sdk.converter.ConverterFactory}.
   */
  boolean retrofit;

  /**
   * Retrofit mode: the team's root model class simple name the config's typed getters bind to
   * (e.g. {@code SscsCaseData}). Null in generate mode (the emitters then use {@code CaseData}).
   */
  String retrofitCaseDataClass;

  /**
   * Retrofit mode: the team's {@code State} enum simple name to reference, or null to reference the
   * fresh generated {@code State} enum (no reusable enum, or generate mode).
   */
  String retrofitStateClass;

  /**
   * Retrofit mode: the package the reused {@code State} enum lives in, when
   * {@link #retrofitStateClass} names an existing enum (may differ from {@link #modelPackage}).
   */
  String retrofitStateClassPackage;

  /**
   * Retrofit mode: CCD state ID → the reused {@code State} enum's Java constant name, for a reused
   * enum whose constants differ from the CCD IDs (e.g. {@code PREPARE_FOR_HEARING → CASE_MANAGEMENT}
   * via {@code @JsonProperty}). Null / empty in generate mode, where the constant name equals the
   * state ID.
   */
  Map<String, String> retrofitStateConstants;

  /**
   * Retrofit mode: the field-count threshold above which synthesised definition-only fields are
   * moved into a {@code CaseDataExtra} class (added as a prefix-less {@code @JsonUnwrapped} member)
   * rather than appended to the root class, to stay under the JVM/Lombok all-args-constructor limit
   * (finding B2). Zero (the default) means use the converter's built-in safe threshold; a positive
   * value overrides it (primarily for focused round-trip tests that trip the limit with a small
   * fixture).
   */
  int retrofitConstructorLimit;

  /**
   * Retrofit mode: simple type name → its real fully-qualified name in the team's model, for types a
   * companion complex type references that live in a DIFFERENT sub-package than the companion's
   * ({@code modelPackage}). Generate mode assumes every type sits in {@code modelPackage}; a real
   * model scatters them (Civil's {@code JudgmentAddress} in {@code model.judgmentonline},
   * {@code PaymentStatus} in {@code enums}, …), so {@code JavaTypeParser} consults this map before
   * defaulting an unqualified name to {@code modelPackage}. Null / empty in generate mode.
   */
  Map<String, String> retrofitTypeFqnOverrides;

  /**
   * Retrofit mode: operator-supplied disambiguation for a simple type name the definition references
   * that is declared in more than one model sub-package with nothing in the {@code ComplexTypes} JSON
   * to choose between them (finding D1 — Civil's {@code HearingLength}, {@code CaseLocationCivil}).
   * Maps the ambiguous simple name to the fully-qualified package the retrofit resolver should bind
   * it to; consulted before the resolver's refuse-to-guess so the type resolves instead of defaulting
   * (wrongly) to {@code modelPackage}. Supplied via the repeatable {@code --type-package-hint
   * TypeName=fully.qualified.package} CLI option. Null / empty when no hints are given.
   */
  Map<String, String> retrofitTypePackageHints;

  /**
   * Retrofit mode: the model REPO root the emitted patch paths should be relative to, so every lane's
   * patch is rooted the same way and {@code bin/retrofit-verify} applies it uniformly (patch-root
   * consistency). The emitter prepends {@code modelSourceRoot} relative to this (e.g.
   * {@code service/src/main/java/}) to each diff path. Null defaults to the source root itself (empty
   * prefix — paths relative to {@code --model-source-root}, the historical behaviour).
   */
  Path retrofitModelRepoRoot;

  /**
   * Retrofit mode: the simple names of every <em>enum</em> in the team's model, reserved when naming
   * generated <em>complex-type</em> companions (finding #3). A complex-type companion is emitted only
   * when the definition type does not bind to an existing model class (see
   * {@code RetrofitComplexTypeEmitter}), so the sole same-named type it can clash with is an enum
   * (e.g. the definition {@code benefit} complex type PascalCased to {@code Benefit} vs the sscs
   * domain enum {@code Benefit}). Reserving these suffixes the companion ({@code Benefit2}) rather
   * than emitting a {@code duplicate class}; the CCD wire ID round-trips via {@code @ComplexType(name)}.
   * Model class names are deliberately NOT reserved here — a complex type matching an existing class
   * binds to it (no companion), so reserving it would wrongly suffix the reference to a type that is
   * never emitted. Null/empty in generate mode.
   */
  java.util.Set<String> retrofitReservedComplexTypeNames;

  /**
   * Retrofit mode: the simple names of <em>every</em> type in the team's model, reserved when naming
   * generated <em>fixed-list</em> enum companions (finding #4). A fixed-list companion reuses a model
   * enum only on an exact list-ID match (rebind drops those), so a machine {@code FL_}-prefixed or
   * case-shifted ID emits a fresh companion that can collide with a model enum OR a class of the
   * PascalCased name ({@code FL_amendReason} → {@code AmendReason} enum; {@code FL_caseOutcome} →
   * {@code CaseOutcome} class). Reserving all names is safe because the only names that must stay free
   * are the exact-ID reuses, which emit no companion. Broader than its complex-type counterpart
   * {@link #retrofitReservedComplexTypeNames} for that reason. Null/empty in generate mode.
   */
  java.util.Set<String> retrofitReservedFixedListNames;

  /**
   * Retrofit mode: a view of the team's declared model classes, consulted by the linker's
   * {@code EventComplexTypeResolver} so a {@code CaseEventToComplexTypes} member chain binds to the
   * class the complex field is <em>actually declared as</em> (its real getters) rather than the
   * SDK-predefined type of the same complex-type ID or a similarly-named synthesised sibling. Null in
   * generate mode, where the resolver walks only generated complex types and reflected SDK types.
   */
  uk.gov.hmcts.ccd.sdk.converter.model.RetrofitModelTypeGraph retrofitModelTypeGraph;
}
