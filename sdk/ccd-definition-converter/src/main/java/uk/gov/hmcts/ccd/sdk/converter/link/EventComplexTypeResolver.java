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
    if (generatedTypes != null) {
      for (ComplexTypeModel model : generatedTypes) {
        generatedById.put(model.getId(), model);
      }
    }
    this.predefinedFqnById = predefinedFqnById == null ? Map.of() : predefinedFqnById;
  }

  /**
   * The complex-type ID a case field declares, or null when the field is not a directly-complex
   * field the emitter can open a {@code .complex(getter)} member block on. Only a field whose
   * {@code FieldType} directly names a walkable complex type qualifies: a {@code Collection} field's
   * getter is typed {@code List<ListValue<X>>}, so {@code .complex(getter)} types the member builder
   * on the list rather than the element {@code X} and a {@code .mandatory(X::getMember)} inside it
   * would not compile — such groups stay a row passthrough.
   *
   * @param field the case field
   * @return the root complex-type ID, or null when the field is not a directly-walkable complex type
   */
  public String rootTypeId(FieldModel field) {
    String fieldType = field.getFieldType();
    if (fieldType != null && isKnownType(fieldType)) {
      return fieldType;
    }
    return null;
  }

  private boolean isKnownType(String id) {
    return generatedById.containsKey(id) || predefinedFqnById.containsKey(id);
  }

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
    if (listElementCode == null || listElementCode.isEmpty()) {
      return Optional.empty();
    }
    String[] segments = listElementCode.split("\\.", -1);
    List<EventComplexTypeGroup.Hop> hops = new ArrayList<>();
    Object currentType = typeNode(rootTypeId);
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
      // An intermediate segment must resolve to a nested complex type to descend into.
      Object next = member.nestedType;
      if (next == null) {
        return Optional.empty();
      }
      hops.add(EventComplexTypeGroup.Hop.builder()
          .declaringType(typeRefOf(currentType))
          .getter(member.getter)
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
    if (typeNode instanceof ComplexTypeModel model) {
      for (FieldModel member : model.getMembers()) {
        if (segment.equals(member.getId())) {
          Object nested = typeNode(nestedTypeId(member));
          // The generated member's @CCD(hint) is emitted by CaseEventToComplexTypesGenerator as the
          // row's HintText regardless of the event override, so it must match the input row's
          // HintText or the group falls back (see deriveEventComplexTypeGroup).
          return new ResolvedMember(
              "get" + capitalise(member.getJavaName()), nested, member.getHint());
        }
      }
      return null;
    }
    Class<?> clazz = (Class<?>) typeNode;
    for (Field field : allFields(clazz)) {
      if (segment.equals(effectiveId(field))) {
        Class<?> fieldType = field.getType();
        Object nested = fieldType != null && isReflectableComplex(fieldType) ? fieldType : null;
        // A predefined SDK type carries no @CCD(hint) on its members, so no HintText leaks.
        return new ResolvedMember("get" + capitalise(field.getName()), nested, null);
      }
    }
    return null;
  }

  /**
   * The complex-type ID an intermediate generated member declares, for descending into a
   * {@code .complex(hop)} — its {@code FieldType} when that directly names a complex type. A
   * {@code Collection} member is not descended into (its getter is typed {@code List<ListValue<X>>},
   * so a nested {@code .complex(hop)} on it would not compile); returning null makes any
   * {@code ListElementCode} that would descend through a collection member fall back to a row
   * passthrough.
   */
  private String nestedTypeId(FieldModel member) {
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

  private record ResolvedMember(String getter, Object nestedType, String declaredHint) {
  }
}
