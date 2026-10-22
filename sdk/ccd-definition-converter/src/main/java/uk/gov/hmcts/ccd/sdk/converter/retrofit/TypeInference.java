package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.Optional;
import java.util.Set;

/**
 * Infers the CCD {@code FieldType} the SDK would emit for a declared Java type, mirroring
 * {@code CaseFieldGenerator.resolveFieldType}/{@code resolveCollectionElementType}: {@code String}
 * → {@code Text}, {@code LocalDate} → {@code Date}, an enum → {@code FixedRadioList}, a
 * {@code Collection} → {@code Collection} with the element as its type parameter (descending into a
 * generic element's inner argument, but <b>not</b> into a concrete value-bearing wrapper — the SDK's
 * {@code hasGenerics()} guard). The result feeds the EXACT-vs-TYPE_CONFLICT classification.
 */
final class TypeInference {

  /**
   * Collection raw types the SDK treats structurally (via {@code Collection.isAssignableFrom}).
   */
  private static final Set<String> COLLECTIONS = Set.of(
      "List", "Set", "Collection", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet",
      "SortedSet", "TreeSet");

  private static final Set<String> NUMBER_TYPES = Set.of(
      "int", "long", "float", "double", "Integer", "Long", "Float", "Double", "BigDecimal");

  private final ModelSourceIndex index;

  TypeInference(ModelSourceIndex index) {
    this.index = index;
  }

  /** The inferred CCD type for a Java field, plus collection-wrapper diagnostics. */
  static final class Inferred {
    /**
     * The inferred CCD {@code FieldType} (e.g. {@code Text}, {@code Collection}, a complex name).
     */
    final String fieldType;
    /**
     * The inferred {@code FieldTypeParameter} (collection element / fixed-list enum), or null.
     */
    final String fieldTypeParameter;
    /**
     * True when the field is a {@code List<ConcreteWrapper>} whose element is a non-generic
     * {@code value}-bearing class the SDK's {@code hasGenerics()} descent does NOT unwrap — so the
     * SDK would emit the wrapper name as the parameter and the field needs
     * {@code @CCD(typeParameterOverride=…)} (proposal decision 8).
     */
    final boolean concreteWrapper;
    /** True when the field is any kind of collection (generic or concrete wrapper). */
    final boolean collection;

    Inferred(String fieldType, String fieldTypeParameter, boolean concreteWrapper,
        boolean collection) {
      this.fieldType = fieldType;
      this.fieldTypeParameter = fieldTypeParameter;
      this.concreteWrapper = concreteWrapper;
      this.collection = collection;
    }
  }

  /**
   * Infers the CCD type for a resolved property.
   *
   * @param property the resolved model property
   * @return the inferred CCD type
   */
  Inferred infer(ResolvedProperty property) {
    Type declared = property.declaredType;
    if (!(declared instanceof ClassOrInterfaceType cit)) {
      // A primitive (int, boolean, …) — map the numeric ones, otherwise carry the raw name.
      String name = declared.asString();
      if (NUMBER_TYPES.contains(name)) {
        return leaf("Number");
      }
      if ("boolean".equals(name)) {
        return leaf("YesOrNo");
      }
      return leaf(name);
    }

    String raw = cit.getNameAsString();
    if (COLLECTIONS.contains(raw)) {
      return inferCollection(property, cit);
    }
    return inferSimple(property, cit, raw);
  }

  private Inferred inferSimple(ResolvedProperty property, ClassOrInterfaceType cit, String raw) {
    Optional<ModelSourceIndex.Type> resolved = index.resolve(property.context, cit);

    // Enum → FixedRadioList, unless it carries @ComplexType(generate = false) (the SDK's
    // resolveSimpleType guard) — an enum that IS a predefined complex type (SDK YesOrNo) is not a
    // fixed list. A same-named @ComplexType(name) then overrides the emitted type name.
    if (resolved.map(ModelSourceIndex.Type::isEnum).orElse(false)) {
      ModelSourceIndex.Type enumType = resolved.get();
      if (generatesAsEnum(enumType)) {
        return new Inferred("FixedRadioList", raw, false, false);
      }
      return leaf(complexTypeName(enumType).orElse(raw));
    }

    // A JDK leaf type (not in the parsed source): map exactly as CaseFieldGenerator.resolveSimpleType.
    switch (raw) {
      case "String" -> {
        return leaf("Text");
      }
      case "LocalDate" -> {
        return leaf("Date");
      }
      case "LocalDateTime" -> {
        return leaf("DateTime");
      }
      case "Boolean" -> {
        return leaf("YesOrNo");
      }
      default -> {
        if (NUMBER_TYPES.contains(raw)) {
          return leaf("Number");
        }
      }
    }
    // A complex type (generated class, SDK-predefined, or team POJO): the SDK emits the type's own
    // name, or its @ComplexType(name) when annotated. An out-of-source SDK type (YesOrNo, AddressUK,
    // Document, …) keeps its simple name, which is exactly its CCD FieldType.
    String complexName = resolved.flatMap(TypeInference::complexTypeName).orElse(raw);
    return leaf(complexName);
  }

  private static boolean generatesAsEnum(ModelSourceIndex.Type enumType) {
    Optional<com.github.javaparser.ast.expr.AnnotationExpr> complex =
        Annotations.find(enumType.decl, "ComplexType");
    if (complex.isEmpty()) {
      return true;
    }
    // @ComplexType present: the SDK skips the FixedRadioList branch when generate = false.
    return !Annotations.find(enumType.decl, "ComplexType")
        .map(ann -> hasGenerateFalse(ann))
        .orElse(false);
  }

  private static boolean hasGenerateFalse(com.github.javaparser.ast.expr.AnnotationExpr ann) {
    if (ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
      return normal.getPairs().stream()
          .anyMatch(p -> "generate".equals(p.getNameAsString())
              && "false".equals(p.getValue().toString()));
    }
    return false;
  }

  private Inferred inferCollection(ResolvedProperty property, ClassOrInterfaceType cit) {
    Optional<ClassOrInterfaceType> elementRef = firstTypeArgument(cit);
    if (elementRef.isEmpty()) {
      // A raw List with no element type — the SDK cannot resolve the element, so treat it as an
      // opaque collection of String (the linker's default) rather than crashing.
      return new Inferred("Collection", "String", false, true);
    }
    ClassOrInterfaceType element = elementRef.get();
    Optional<ClassOrInterfaceType> inner = firstTypeArgument(element);
    if (inner.isPresent()) {
      // Generic wrapper (Element<X>, ListValue<X>, GenericTypeItem<X>): the SDK descends to X.
      String innerName = inner.get().getNameAsString();
      boolean multiSelect = "Set".equals(cit.getNameAsString()) && isEnum(property, inner.get());
      return new Inferred(multiSelect ? "MultiSelectList" : "Collection", innerName, false, true);
    }
    // Non-generic element. Set<enum> is a MultiSelectList; otherwise the element is the parameter.
    if (isEnum(property, element)) {
      String elementName = element.getNameAsString();
      return new Inferred(
          "Set".equals(cit.getNameAsString()) ? "MultiSelectList" : "Collection",
          elementName, false, true);
    }
    String elementName = element.getNameAsString();
    boolean concreteWrapper = isConcreteValueWrapper(property, element);
    return new Inferred("Collection", elementName, concreteWrapper, true);
  }

  /**
   * Whether a non-generic collection element is a value-bearing wrapper the SDK would mis-resolve:
   * a class carrying a {@code value} field, or a concrete subclass of a parameterised generic base
   * (e.g. {@code DocumentTypeItem extends GenericTypeItem<DocumentType>}, {@code SscsDocument
   * extends AbstractDocument<D>}).
   */
  private boolean isConcreteValueWrapper(ResolvedProperty property, ClassOrInterfaceType element) {
    Optional<ModelSourceIndex.Type> resolved = index.resolve(property.context, element);
    if (resolved.isEmpty()) {
      return false;
    }
    ModelSourceIndex.Type type = resolved.get();
    if (hasValueField(type)) {
      return true;
    }
    // Concrete subclass binding a generic base's type parameter (extends Foo<Bar>).
    if (type.decl.isClassOrInterfaceDeclaration()) {
      var extended = type.decl.asClassOrInterfaceDeclaration().getExtendedTypes();
      if (!extended.isEmpty() && firstTypeArgument(extended.get(0)).isPresent()) {
        return true;
      }
    }
    return false;
  }

  private boolean hasValueField(ModelSourceIndex.Type type) {
    ModelSourceIndex.Type current = type;
    int guard = 0;
    while (current != null && guard++ < 10) {
      ModelSourceIndex.Type owner = current;
      for (FieldDeclaration field : owner.decl.findAll(FieldDeclaration.class)) {
        if (field.getParentNode().map(p -> p == owner.decl).orElse(false)
            && field.getVariables().stream().anyMatch(v -> "value".equals(v.getNameAsString()))) {
          return true;
        }
      }
      current = superclass(current).orElse(null);
    }
    return false;
  }

  private Optional<ModelSourceIndex.Type> superclass(ModelSourceIndex.Type type) {
    if (!type.decl.isClassOrInterfaceDeclaration()) {
      return Optional.empty();
    }
    var extended = type.decl.asClassOrInterfaceDeclaration().getExtendedTypes();
    return extended.isEmpty() ? Optional.empty() : index.resolve(type.unit, extended.get(0));
  }

  private boolean isEnum(ResolvedProperty property, ClassOrInterfaceType ref) {
    return index.resolve(property.context, ref)
        .map(ModelSourceIndex.Type::isEnum)
        .orElse(false);
  }

  private static Optional<ClassOrInterfaceType> firstTypeArgument(ClassOrInterfaceType type) {
    return type.getTypeArguments()
        .flatMap(args -> args.isEmpty() ? Optional.empty() : Optional.of(args.get(0)))
        .filter(t -> t instanceof ClassOrInterfaceType)
        .map(t -> (ClassOrInterfaceType) t);
  }

  private static Optional<String> complexTypeName(ModelSourceIndex.Type type) {
    return Annotations.find(type.decl, "ComplexType")
        .flatMap(ann -> Annotations.stringMember(ann, "name"))
        .filter(name -> !name.isEmpty());
  }

  private static Inferred leaf(String fieldType) {
    return new Inferred(fieldType, null, false, false);
  }
}
