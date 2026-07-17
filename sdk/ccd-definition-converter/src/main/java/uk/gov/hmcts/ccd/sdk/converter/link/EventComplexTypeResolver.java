package uk.gov.hmcts.ccd.sdk.converter.link;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.EventComplexTypeGroup;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.RetrofitModelTypeGraph;

/**
 * Resolves a {@code CaseEventToComplexTypes} row's dotted {@code ListElementCode} into a typed
 * getter chain rooted at a complex {@code CaseData} field, so the converter can emit the row as a
 * {@code .complex(CaseData::getField).<ctx>(Type::getMember)} builder call rather than passing it
 * through as raw JSON.
 *
 * <p>The walk mirrors the SDK's own naming math exactly, because the SDK's generator re-derives the
 * emitted {@code ListElementCode} from the getter chain via {@link uk.gov.hmcts.ccd.sdk.FieldUtils}:
 * a segment matches a member whose effective CCD id equals it, where the id of a generated
 * complex-type member is its declared {@code ListElementCode} (carried on {@link FieldModel#getId()})
 * and the id of an SDK-predefined member is {@code @JsonProperty.value()} or, absent that, the Java
 * field name (never capitalised at the top level — {@code FieldUtils.getFieldId} only capitalises
 * when prefixing). The getter is {@code get + capitalize(javaName)} for a generated member and
 * {@code get + capitalize(field.getName())} for a predefined one.
 *
 * <p>A predefined type ({@code uk.gov.hmcts.ccd.sdk.type.*}) is reflected on the converter's own
 * classpath (it depends on {@code ccd-config-generator}); a generated type is walked through its
 * {@link ComplexTypeModel}. When any segment cannot be resolved — an unknown member, a segment that
 * lands on a scalar yet has further segments, a type the converter neither generated nor can reflect
 * — resolution fails and the caller keeps the row as a passthrough.
 */
public final class EventComplexTypeResolver {

  private final Map<String, ComplexTypeModel> generatedById = new LinkedHashMap<>();
  private final Map<String, String> predefinedFqnById;
  private final RetrofitModelTypeGraph modelGraph;

  /**
   * Creates a resolver over the case type's generated complex types and the SDK-predefined types.
   *
   * @param generatedTypes the generated complex types (id → model), for walking converter-emitted
   *     members
   * @param predefinedFqnById the SDK-predefined complex-type IDs mapped to their Java FQN, for
   *     reflecting built-in members (e.g. {@code ChangeOrganisationRequest} → its SDK class)
   */
  public EventComplexTypeResolver(
      List<ComplexTypeModel> generatedTypes, Map<String, String> predefinedFqnById) {
    this(generatedTypes, predefinedFqnById, null);
  }

  /**
   * Creates a resolver that, in retrofit mode, binds a complex field's root type and every member
   * chain to the team's <em>actual declared</em> model classes via {@code modelGraph} — so a
   * {@code CaseEventToComplexTypes} chain references the class the field is really typed as (with its
   * real getters, e.g. {@code getOrganisationID}) rather than the SDK-predefined type of the same
   * complex-type ID or a similarly-named synthesised sibling. When the model graph has no binding for
   * a field (a genuinely SDK-typed field) the walk falls back to the generated / SDK-predefined path.
   *
   * @param generatedTypes the generated complex types (id → model)
   * @param predefinedFqnById the SDK-predefined complex-type IDs mapped to their Java FQN
   * @param modelGraph the team's model type graph for retrofit binding, or null in generate mode
   */
  public EventComplexTypeResolver(
      List<ComplexTypeModel> generatedTypes, Map<String, String> predefinedFqnById,
      RetrofitModelTypeGraph modelGraph) {
    if (generatedTypes != null) {
      for (ComplexTypeModel model : generatedTypes) {
        generatedById.put(model.getId(), model);
      }
    }
    this.predefinedFqnById = predefinedFqnById == null ? Map.of() : predefinedFqnById;
    this.modelGraph = modelGraph;
  }

  /**
   * The walkable complex-type ID a case field declares, or null when the field is not one the
   * emitter can open a {@code .complex(...)} member block on. A field whose {@code FieldType}
   * directly names a walkable complex type resolves to that type; a {@code Collection} field
   * ({@code FieldType=Collection}) resolves to its element type ({@code FieldTypeParameter}) — the
   * emitter opens the scope with the two-arg element-typed {@code .complex(getter, Element.class)}
   * overload rather than the one-arg form (see {@link #rootElementType}). A field naming neither
   * (scalar, fixed list, unknown type) stays a row passthrough.
   *
   * @param field the case field
   * @return the root walkable complex-type ID (its element type for a collection), or null
   */
  public String rootTypeId(FieldModel field) {
    String fieldType = field.getFieldType();
    if (fieldType == null) {
      return null;
    }
    if (COLLECTION.equals(fieldType)) {
      String elementId = field.getFieldTypeParameter();
      return elementId != null && isKnownType(elementId) ? elementId : null;
    }
    if (isKnownType(fieldType)) {
      return fieldType;
    }
    return null;
  }

  /**
   * The type node the walk of a case field's members starts at: in retrofit mode the team's actual
   * declared model type (a scalar complex field's declared class, or a {@code Collection} field's
   * element class), else the generated / SDK-predefined type named by the field's {@code FieldType}
   * ({@code FieldTypeParameter} for a collection). Null when the field is not one the emitter can open
   * a {@code .complex(...)} block on (see {@link #rootTypeId}) or the retrofit binding is missing and
   * the type-id path also cannot resolve it.
   *
   * <p>Preferring the model binding is what fixes the SDK-type-vs-model-type defect: a field whose CCD
   * type ID is {@code ChangeOrganisationRequest} / {@code Organisation} / {@code AddressUK} but whose
   * declared type is the team's own same-shaped class walks the team's class (real getters), instead
   * of the SDK-predefined class the shared type ID would otherwise select.
   *
   * @param field the case field
   * @return the root type node, or null
   */
  public Object rootNode(FieldModel field) {
    if (modelGraph != null) {
      Optional<RetrofitModelTypeGraph.Handle> bound = modelGraph.rootHandle(field.getId());
      if (bound.isPresent()) {
        return bound.get();
      }
      // No model binding for this field (a genuinely SDK-typed / definition-only field): fall through
      // to the generated / SDK-predefined type-id node so those fields keep resolving as before.
    }
    String id = rootTypeId(field);
    return id == null ? null : typeNode(id);
  }

  /**
   * The element {@link EventComplexTypeGroup.TypeRef} of a {@code Collection}-typed root field, or
   * null when the root is a scalar complex field. When non-null the emitter must open the root scope
   * with the two-arg {@code .complex(getter, Element.class)} overload; when null the plain one-arg
   * {@code .complex(getter)} suffices (see {@link #rootTypeId}).
   *
   * @param field the case field
   * @return the element type ref for a resolvable collection root, else null
   */
  public EventComplexTypeGroup.TypeRef rootElementType(FieldModel field) {
    if (modelGraph != null) {
      Optional<RetrofitModelTypeGraph.Handle> bound = modelGraph.rootHandle(field.getId());
      if (bound.isPresent()) {
        // A collection field's rootHandle is its ELEMENT type; the emitter opens the element-typed
        // scope only when the declared model type is actually a list.
        return modelGraph.rootIsCollection(field.getId()) ? typeRefOf(bound.get()) : null;
      }
    }
    if (!COLLECTION.equals(field.getFieldType())) {
      return null;
    }
    String elementId = field.getFieldTypeParameter();
    if (elementId == null) {
      return null;
    }
    Object node = typeNode(elementId);
    return node == null ? null : typeRefOf(node);
  }

  private boolean isKnownType(String id) {
    return generatedById.containsKey(id) || predefinedFqnById.containsKey(id);
  }

  /**
   * The input {@code FieldType} value marking a collection member ({@code List<ListValue<X>>}).
   */
  private static final String COLLECTION = "Collection";

  /**
   * Resolves one {@code ListElementCode} into a getter chain rooted at {@code rootTypeId}.
   *
   * @param rootTypeId the declared complex-type ID of the owning case field (see
   *     {@link #rootTypeId})
   * @param listElementCode the dotted member path, e.g. {@code OrganisationToAdd.OrganisationID}
   * @param contextMethod the DisplayContext builder method ({@code optional}/{@code mandatory}/
   *     {@code readonly})
   * @param showCondition {@code FieldShowCondition}, or null
   * @param eventLabel {@code EventElementLabel}, or null
   * @param eventHint {@code EventHintText}, or null
   * @param pageId {@code PageID}, or null
   * @return the resolved member, or empty when any segment cannot be resolved
   */
  public Optional<EventComplexTypeGroup.Member> resolve(
      String rootTypeId, String listElementCode, String contextMethod,
      String showCondition, String eventLabel, String eventHint, String pageId) {
    return resolve(typeNode(rootTypeId), listElementCode, contextMethod,
        showCondition, eventLabel, eventHint, pageId);
  }

  /**
   * Resolves one {@code ListElementCode} into a getter chain rooted at a resolved type node (a
   * generated {@link ComplexTypeModel}, a reflected SDK-predefined {@link Class}, or — in retrofit
   * mode — a {@link RetrofitModelTypeGraph.Handle} onto the team's own declared class). Used with
   * {@link #rootNode} so the whole chain binds to the field's actual declared type.
   *
   * @param rootNode the root type node the walk starts at (see {@link #rootNode})
   * @param listElementCode the dotted member path, e.g. {@code OrganisationToAdd.OrganisationID}
   * @param contextMethod the DisplayContext builder method ({@code optional}/{@code mandatory}/
   *     {@code readonly})
   * @param showCondition {@code FieldShowCondition}, or null
   * @param eventLabel {@code EventElementLabel}, or null
   * @param eventHint {@code EventHintText}, or null
   * @param pageId {@code PageID}, or null
   * @return the resolved member, or empty when any segment cannot be resolved
   */
  public Optional<EventComplexTypeGroup.Member> resolve(
      Object rootNode, String listElementCode, String contextMethod,
      String showCondition, String eventLabel, String eventHint, String pageId) {
    if (listElementCode == null || listElementCode.isEmpty()) {
      return Optional.empty();
    }
    String[] segments = listElementCode.split("\\.", -1);
    List<EventComplexTypeGroup.Hop> hops = new ArrayList<>();
    Object currentType = rootNode;
    if (currentType == null) {
      return Optional.empty();
    }
    for (int i = 0; i < segments.length; i++) {
      String segment = segments[i];
      if (segment.isEmpty()) {
        return Optional.empty();
      }
      ResolvedMember member = member(currentType, segment);
      if (member == null) {
        return Optional.empty();
      }
      boolean last = i == segments.length - 1;
      if (last) {
        return Optional.of(EventComplexTypeGroup.Member.builder()
            .hops(hops)
            .leafType(typeRefOf(currentType))
            .leafGetter(member.getter)
            .contextMethod(contextMethod)
            .showCondition(showCondition)
            .eventLabel(eventLabel)
            .eventHint(eventHint)
            .pageId(pageId)
            .declaredHint(member.declaredHint())
            .build());
      }
      // An intermediate segment must resolve to a nested complex type to descend into — either a
      // scalar complex member or a Collection member (whose element type is walked into via the
      // two-arg element-typed .complex(getter, Element.class) scope the emitter opens for it).
      Object next = member.nestedType;
      if (next == null) {
        return Optional.empty();
      }
      hops.add(EventComplexTypeGroup.Hop.builder()
          .declaringType(typeRefOf(currentType))
          .getter(member.getter)
          .elementType(member.collectionElementRef)
          .build());
      currentType = next;
    }
    return Optional.empty();
  }

  /**
   * A type node the walk descends through: a {@link ComplexTypeModel} or a reflected {@link Class}.
   */
  private Object typeNode(String typeId) {
    ComplexTypeModel generated = generatedById.get(typeId);
    if (generated != null) {
      return generated;
    }
    String fqn = predefinedFqnById.get(typeId);
    if (fqn != null) {
      try {
        return Class.forName(fqn);
      } catch (ClassNotFoundException e) {
        return null;
      }
    }
    return null;
  }

  private EventComplexTypeGroup.TypeRef typeRefOf(Object typeNode) {
    if (typeNode instanceof RetrofitModelTypeGraph.Handle handle) {
      // The team's own declared model class, named by its FQN so the emitter imports the exact
      // class (it may live in any sub-package) rather than assuming the model package.
      return EventComplexTypeGroup.TypeRef.builder().modelFqn(handle.fqn()).build();
    }
    if (typeNode instanceof ComplexTypeModel model) {
      String simple = model.getJavaClassName() != null ? model.getJavaClassName() : model.getId();
      return EventComplexTypeGroup.TypeRef.builder().simpleName(simple).build();
    }
    Class<?> clazz = (Class<?>) typeNode;
    return EventComplexTypeGroup.TypeRef.builder().predefinedFqn(clazz.getName()).build();
  }

  /**
   * Resolves a single segment against a type node, returning the leaf getter and — when the member
   * is itself a complex type the walk can continue into — the nested type node.
   */
  private ResolvedMember member(Object typeNode, String segment) {
    if (typeNode instanceof RetrofitModelTypeGraph.Handle handle) {
      Optional<RetrofitModelTypeGraph.MemberResolution> resolved = modelGraph.member(handle, segment);
      if (resolved.isEmpty()) {
        return null;
      }
      RetrofitModelTypeGraph.MemberResolution m = resolved.get();
      RetrofitModelTypeGraph.Handle nested = m.nested();
      EventComplexTypeGroup.TypeRef elementRef =
          m.collection() && nested != null ? typeRefOf(nested) : null;
      return new ResolvedMember(m.getter(), nested, m.declaredHint(), elementRef);
    }
    if (typeNode instanceof ComplexTypeModel model) {
      for (FieldModel member : model.getMembers()) {
        if (segment.equals(member.getId())) {
          String nestedId = nestedTypeId(member);
          Object nested = typeNode(nestedId);
          // A Collection member descends into its element type via the two-arg element-typed
          // .complex(getter, Element.class) scope; carry the element type ref so the emitter opens
          // the hop with that overload. A scalar complex member carries none (one-arg .complex).
          EventComplexTypeGroup.TypeRef elementRef =
              COLLECTION.equals(member.getFieldType()) && nested != null ? typeRefOf(nested) : null;
          // The generated member's @CCD(hint) is emitted by CaseEventToComplexTypesGenerator as the
          // row's HintText unless the placement overrides it; carried so deriveEventComplexTypeGroup
          // can pick the .hintText/.noHintText/unset disposition against the input row's HintText.
          return new ResolvedMember(
              "get" + capitalise(member.getJavaName()), nested, member.getHint(), elementRef);
        }
      }
      return null;
    }
    Class<?> clazz = (Class<?>) typeNode;
    for (Field field : allFields(clazz)) {
      if (segment.equals(effectiveId(field))) {
        Class<?> elementClass = listValueElementType(field);
        if (elementClass != null) {
          // A predefined List<ListValue<X>> collection member: descend into the element type X via
          // the two-arg element-typed scope when X is itself a reflectable complex type.
          Object nested = isReflectableComplex(elementClass) ? elementClass : null;
          EventComplexTypeGroup.TypeRef elementRef = nested == null ? null : typeRefOf(nested);
          return new ResolvedMember(
              "get" + capitalise(field.getName()), nested, null, elementRef);
        }
        Class<?> fieldType = field.getType();
        Object nested = fieldType != null && isReflectableComplex(fieldType) ? fieldType : null;
        // A predefined SDK type carries no @CCD(hint) on its members, so no HintText leaks.
        return new ResolvedMember("get" + capitalise(field.getName()), nested, null, null);
      }
    }
    return null;
  }

  /**
   * The element type {@code X} of a reflected {@code List<ListValue<X>>} collection member, or null
   * when the field is not such a collection. Mirrors the SDK's own list-element resolution: a
   * {@code Collection} member is a {@code java.util.List} whose element is a
   * {@code uk.gov.hmcts.ccd.sdk.type.ListValue<X>}.
   */
  private Class<?> listValueElementType(Field field) {
    if (!java.util.List.class.isAssignableFrom(field.getType())) {
      return null;
    }
    if (!(field.getGenericType() instanceof java.lang.reflect.ParameterizedType listType)) {
      return null;
    }
    java.lang.reflect.Type[] listArgs = listType.getActualTypeArguments();
    if (listArgs.length != 1
        || !(listArgs[0] instanceof java.lang.reflect.ParameterizedType listValueType)) {
      return null;
    }
    if (!(listValueType.getRawType() instanceof Class<?> rawListValue)
        || !rawListValue.getName().equals("uk.gov.hmcts.ccd.sdk.type.ListValue")) {
      return null;
    }
    java.lang.reflect.Type[] elementArgs = listValueType.getActualTypeArguments();
    return elementArgs.length == 1 && elementArgs[0] instanceof Class<?> element ? element : null;
  }

  /**
   * The complex-type ID an intermediate generated member declares, for descending into it: its
   * {@code FieldType} when that directly names a complex type, or its {@code FieldTypeParameter}
   * (the element type) when it is a {@code Collection}. The emitter opens a collection hop with the
   * two-arg element-typed {@code .complex(getter, Element.class)} scope and a scalar complex hop with
   * the one-arg {@code .complex(getter)}.
   */
  private String nestedTypeId(FieldModel member) {
    if (COLLECTION.equals(member.getFieldType())) {
      return member.getFieldTypeParameter();
    }
    return member.getFieldType();
  }

  /** The CCD id of a reflected member: {@code @JsonProperty.value()} when present, else the field
   * name — exactly {@link uk.gov.hmcts.ccd.sdk.FieldUtils#getFieldId(Field)} with no prefix. */
  private String effectiveId(Field field) {
    JsonProperty j = field.getAnnotation(JsonProperty.class);
    return j != null && !j.value().isEmpty() ? j.value() : field.getName();
  }

  /** Whether a reflected field type is a complex type the resolver can descend into (an SDK type
   * package class); scalars, enums, JDK types and collections are leaves here. */
  private boolean isReflectableComplex(Class<?> type) {
    return type.getName().startsWith("uk.gov.hmcts.ccd.sdk.type.")
        && !type.isEnum();
  }

  /**
   * Upper-cases the first character, leaving the rest unchanged — matching
   * {@code org.apache.commons.lang3.StringUtils.capitalize}, which is how the SDK's
   * {@code FieldUtils.getFieldId} and every generated/predefined getter derive a member's accessor
   * name from its Java field name.
   */
  private static String capitalise(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    return Character.toTitleCase(s.charAt(0)) + s.substring(1);
  }

  private List<Field> allFields(Class<?> clazz) {
    List<Field> fields = new ArrayList<>();
    for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field f : c.getDeclaredFields()) {
        if (!f.isSynthetic() && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
          fields.add(f);
        }
      }
    }
    return fields;
  }

  private record ResolvedMember(String getter, Object nestedType, String declaredHint,
      EventComplexTypeGroup.TypeRef collectionElementRef) {
  }
}
