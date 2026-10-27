package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.Optional;
import java.util.Set;

/**
 * The single descent from a declared Java type to the type token CCD addresses a definition type on —
 * shared by every retrofit decision that has to line a declaration up against a definition type ID.
 *
 * <p>It mirrors {@code CaseFieldGenerator}'s own reading of a field: a scalar declaration IS the token,
 * a collection is descended one level to its element type, and a generic element wrapper
 * ({@code ListValue<X>}, {@code Element<X>}, {@code CcdValue<X>}) is descended through to {@code X},
 * because that is the class whose members CCD names in the {@code FieldTypeParameter}.
 *
 * <p>Extracted so {@link RetrofitPatchEmitter}'s retype (which REWRITES the token) and
 * {@link RetrofitTypeBinder} (which READS it to decide what a definition type is backed by) cannot
 * drift apart: a binder that descended differently from the retype would bind an ID to one class and
 * then rewrite a declaration naming another.
 */
final class RetrofitTypeTokens {

  private RetrofitTypeTokens() {
  }

  /**
   * Collection raw types the SDK treats structurally, mirroring {@link TypeInference}.
   */
  private static final Set<String> COLLECTION_RAW_TYPES = Set.of(
      "List", "Set", "Collection", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet",
      "SortedSet", "TreeSet");

  /**
   * The single type token in {@code declared} that names the definition type, or null when no single
   * name does.
   *
   * <p>Null when the landed token carries type arguments of its own, or the declaration is a raw
   * collection with none. Both mean the declared shape is deeper or vaguer than the SDK's single level
   * of descent resolves, so no single name corresponds to the definition type: sscs's
   * {@code List<CcdValue<CcdValue<String>>>} descends to {@code CcdValue<String>}, whose name is not
   * the type CCD addresses — and for the retype, renaming THAT token yields
   * {@code List<CcdValue<HearingVenueEpimsId<String>>>}, a type that does not take parameters.
   *
   * @param declared the field's declared type
   * @return the naming token, or null when none is unambiguous
   */
  static Type elementToken(Type declared) {
    if (!(declared instanceof ClassOrInterfaceType cit)) {
      return declared;
    }
    if (!isCollectionRawType(cit)) {
      // A scalar declaration: its own name is the token, but only when it is not itself generic —
      // a Wrapper<Foo> declaration does not name Wrapper as the definition's type.
      return cit.getTypeArguments().isPresent() ? null : cit;
    }
    Optional<ClassOrInterfaceType> element = firstTypeArgument(cit);
    if (element.isEmpty()) {
      return null; // a raw collection names no element type
    }
    // A generic element wrapper (ListValue<X>, Element<X>) is descended through to X; a concrete
    // element class IS the type the SDK names.
    Optional<ClassOrInterfaceType> inner = firstTypeArgument(element.get());
    if (inner.isPresent()) {
      return inner.get().getTypeArguments().isPresent() ? null : inner.get();
    }
    return element.get();
  }

  /** The first type argument of a parameterised type, when it is itself a class/interface type. */
  private static Optional<ClassOrInterfaceType> firstTypeArgument(ClassOrInterfaceType type) {
    return type.getTypeArguments()
        .flatMap(args -> args.isEmpty() ? Optional.empty() : Optional.of(args.get(0)))
        .filter(t -> t instanceof ClassOrInterfaceType)
        .map(t -> (ClassOrInterfaceType) t);
  }

  /**
   * Whether a type's raw name is one of the collection types the SDK treats structurally, mirroring
   * {@link TypeInference}'s own set.
   */
  private static boolean isCollectionRawType(ClassOrInterfaceType type) {
    return COLLECTION_RAW_TYPES.contains(type.getNameAsString());
  }
}
