package uk.gov.hmcts.ccd.sdk.converter.emit.model;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;

/**
 * Applies the Lombok annotations for a generated data-shaped class (CaseData or a complex type).
 *
 * <p>Small classes get {@code @Data @Builder @NoArgsConstructor @AllArgsConstructor}. Classes
 * that exceed Java's 255-parameter constructor limit cannot carry {@code @AllArgsConstructor}
 * (nor {@code @Builder}, whose generated {@code build()} also invokes an all-args constructor),
 * so they drop those. Very large classes additionally drop {@code @Data} in favour of
 * {@code @Getter @Setter}, because {@code @Data}'s generated {@code equals}/{@code hashCode}/
 * {@code toString} enumerate every field in a single method and overflow the JVM's 64&nbsp;KB
 * per-method bytecode limit ({@code code too large}) once a class has enough fields (fpl's
 * CARE_SUPERVISION_EPO CaseData). This is safe because nothing in the generated output or the
 * SDK instantiates the class via a constructor or builder, nor relies on its {@code equals}/
 * {@code hashCode}/{@code toString} — the SDK reflects over its fields and getters, and
 * generated config references getters only.
 */
final class LombokAnnotations {

  /**
   * Field count above which the all-args constructor / builder is omitted. Java caps a method
   * (constructor) at 255 parameters; staying a little under leaves headroom.
   */
  static final int ALL_ARGS_FIELD_LIMIT = 250;

  /**
   * Field count above which {@code @Data} is replaced by {@code @Getter @Setter}. {@code @Data}'s
   * {@code equals}/{@code hashCode}/{@code toString} reference every field in one method; past
   * this many fields that method exceeds the JVM's 64&nbsp;KB bytecode limit.
   */
  static final int DATA_FIELD_LIMIT = 500;

  private static final ClassName DATA = ClassName.get("lombok", "Data");
  private static final ClassName GETTER = ClassName.get("lombok", "Getter");
  private static final ClassName SETTER = ClassName.get("lombok", "Setter");
  private static final ClassName BUILDER = ClassName.get("lombok", "Builder");
  private static final ClassName NO_ARGS = ClassName.get("lombok", "NoArgsConstructor");
  private static final ClassName ALL_ARGS = ClassName.get("lombok", "AllArgsConstructor");

  private LombokAnnotations() {
  }

  /**
   * Adds the appropriate Lombok annotations for the class's field count.
   *
   * @param classBuilder the class being built
   * @param fieldCount the number of fields the class will declare
   */
  static void applyDataClass(TypeSpec.Builder classBuilder, int fieldCount) {
    if (fieldCount <= DATA_FIELD_LIMIT) {
      classBuilder.addAnnotation(AnnotationSpec.builder(DATA).build());
    } else {
      classBuilder.addAnnotation(AnnotationSpec.builder(GETTER).build())
          .addAnnotation(AnnotationSpec.builder(SETTER).build());
    }
    if (fieldCount == 0) {
      // A zero-field class (e.g. a complex type all of whose members are overlay-only, so none
      // are emitted onto the base class) cannot carry both @NoArgsConstructor and
      // @AllArgsConstructor — with no fields the all-args constructor IS the no-arg one, and
      // @Builder's synthesised all-args constructor collides too. Only the no-arg constructor is
      // meaningful, so emit that alone.
      classBuilder.addAnnotation(AnnotationSpec.builder(NO_ARGS).build());
    } else if (fieldCount <= ALL_ARGS_FIELD_LIMIT) {
      classBuilder.addAnnotation(AnnotationSpec.builder(BUILDER).build())
          .addAnnotation(AnnotationSpec.builder(NO_ARGS).build())
          .addAnnotation(AnnotationSpec.builder(ALL_ARGS).build());
    } else {
      classBuilder.addAnnotation(AnnotationSpec.builder(NO_ARGS).build());
    }
  }
}
