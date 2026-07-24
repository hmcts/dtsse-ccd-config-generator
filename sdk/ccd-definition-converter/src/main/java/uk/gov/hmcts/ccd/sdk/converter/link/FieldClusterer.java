package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uk.gov.hmcts.ccd.sdk.converter.model.ClusteredFieldRef;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapEntry;

/**
 * Folds families of repeated flat CaseFields into shared complex types referenced by
 * {@code @JsonUnwrapped(prefix=...)}, so the generated {@code CaseData} stays well under Java's
 * 255-field constructor limit while the emitted CCD definition is byte-identical.
 *
 * <p>A "family" is a set of instances that share a common prefix and whose remaining field-name
 * segment (the "member") matches across instances. Two shapes are recognised:
 * <ul>
 *   <li><b>Numbered</b>: {@code applicant1FirstName}, {@code applicant2FirstName}, … — prefix is
 *       {@code applicant} + an index, member is {@code FirstName}. Instances are
 *       {@code applicant1}, {@code applicant2}.</li>
 *   <li><b>Semantic</b>: two or more instances sharing a leading camel segment (e.g.
 *       {@code homeOfficeX}/{@code appellantX}) — used only when the family is homogeneous.</li>
 * </ul>
 *
 * <p>A family is clustered only when it is <b>homogeneous</b>: every instance has the identical
 * set of member names, and each shared member is identical across instances in type, overrides,
 * label/hint/showCondition/regex/category and resolved access classes. This is exactly the
 * condition under which a hand-written {@code @JsonUnwrapped} model would round-trip, because a
 * single member field declaration must serve every instance. Non-homogeneous families are left
 * flat and recorded as an informational gap.
 */
final class FieldClusterer {

  /** Minimum number of instances in a family before clustering is worthwhile. */
  private static final int MIN_INSTANCES = 2;

  /** Minimum members per instance; single-member families are not worth a complex type. */
  private static final int MIN_MEMBERS = 2;

  // A numbered instance prefix: a lower-camel base, one or more digits, then an upper-case
  // member remainder — e.g. applicant1FirstName -> base "applicant", index "1", member
  // "FirstName".
  private static final Pattern NUMBERED =
      Pattern.compile("^([a-z][a-zA-Z]*?)(\\d+)([A-Z].*)$");

  private final GapCollector gaps;

  FieldClusterer(GapCollector gaps) {
    this.gaps = gaps;
  }

  /** The outcome of a clustering run. */
  record Result(List<FieldModel> caseFields,
                List<ComplexTypeModel> synthesizedTypes,
                Map<String, ClusteredFieldRef> refs) {

    /**
     * A no-op result that keeps the fields flat with no synthesised types and no cluster refs —
     * used in retrofit mode, where the team's existing model supplies its own {@code @JsonUnwrapped}
     * structure and synthesising fresh cluster types would invent members the model does not have.
     *
     * @param caseFields the flat case fields
     * @return an unclustered result
     */
    static Result unclustered(List<FieldModel> caseFields) {
      return new Result(caseFields, List.of(), Map.of());
    }
  }

  /**
   * Clusters numbered field families in the given case fields.
   *
   * @param caseFields the flat case fields (with access already attached)
   * @param existingTypeNames complex-type and other type simple-names already in use, to avoid
   *     collisions when naming synthesized types
   * @return the rewritten case-field list, the synthesized complex types, and the flat-ID → ref
   *     map for the config emitter
   */
  Result cluster(List<FieldModel> caseFields, Set<String> existingTypeNames) {
    // Group candidate members by instance prefix (e.g. "applicant1"), keyed within a family by
    // the shared base ("applicant"). Only numbered instances are auto-clustered; the base groups
    // its numbered siblings.
    Map<String, List<Instance>> familiesByBase = new LinkedHashMap<>();
    Map<String, Instance> instanceByPrefix = new LinkedHashMap<>();

    for (FieldModel field : caseFields) {
      if (!isClusterable(field)) {
        continue;
      }
      Matcher m = NUMBERED.matcher(field.getId());
      if (!m.matches()) {
        continue;
      }
      String base = m.group(1);
      String index = m.group(2);
      String member = m.group(3);
      String prefix = base + index;
      Instance instance = instanceByPrefix.computeIfAbsent(prefix,
          p -> new Instance(p, base));
      instance.members.put(member, field);
      familiesByBase.computeIfAbsent(base, b -> new ArrayList<>());
      if (!familiesByBase.get(base).contains(instance)) {
        familiesByBase.get(base).add(instance);
      }
    }

    Set<String> usedTypeNames = new LinkedHashSet<>(existingTypeNames);
    Set<String> clusteredFieldIds = new LinkedHashSet<>();
    List<ComplexTypeModel> synthesized = new ArrayList<>();
    Map<String, ClusteredFieldRef> refs = new LinkedHashMap<>();
    // Parent fields to insert, keyed by the position (first member's index in caseFields) so
    // output order stays stable.
    Map<String, FieldModel> parentByPrefix = new LinkedHashMap<>();

    for (Map.Entry<String, List<Instance>> family : familiesByBase.entrySet()) {
      String base = family.getKey();
      List<Instance> instances = family.getValue();
      if (instances.size() < MIN_INSTANCES) {
        continue;
      }
      if (instances.get(0).members.size() < MIN_MEMBERS) {
        continue;
      }
      if (!isHomogeneous(base, instances)) {
        continue;
      }

      String typeName = uniqueTypeName(base, usedTypeNames);
      usedTypeNames.add(typeName);

      // The complex type's members come from the first instance (all instances are identical),
      // re-homed as plain members (member name from the de-prefixed remainder).
      Instance first = instances.get(0);
      List<FieldModel> members = new ArrayList<>();
      for (Map.Entry<String, FieldModel> e : first.members.entrySet()) {
        members.add(toMember(e.getKey(), e.getValue()));
      }
      synthesized.add(ComplexTypeModel.builder()
          .id(typeName)
          .members(members)
          .depth(0)
          .build());

      for (Instance instance : instances) {
        String parentMember = IdentifierSanitiser.toMemberName(instance.prefix);
        parentByPrefix.put(instance.prefix, FieldModel.builder()
            .id(instance.prefix)
            .javaName(parentMember)
            .fieldType("Complex")
            .fieldTypeParameter(typeName)
            .javaType(typeName)
            .unwrapPrefix(instance.prefix)
            .overlayTags(Set.of())
            .accessClassNames(List.of())
            .build());
        for (Map.Entry<String, FieldModel> e : instance.members.entrySet()) {
          FieldModel memberField = e.getValue();
          clusteredFieldIds.add(memberField.getId());
          refs.put(memberField.getId(), ClusteredFieldRef.builder()
              .parentGetter(getter(parentMember))
              .clusterType(typeName)
              .memberGetter(getter(IdentifierSanitiser.toMemberName(deCapitalise(e.getKey()))))
              .build());
        }
      }
    }

    if (synthesized.isEmpty()) {
      return new Result(caseFields, List.of(), Map.of());
    }

    // Rebuild the flat list: drop clustered member fields; where the first member of an instance
    // sat, insert the synthetic parent (once per prefix, in first-seen order).
    List<FieldModel> rewritten = new ArrayList<>();
    Set<String> insertedParents = new LinkedHashSet<>();
    for (FieldModel field : caseFields) {
      if (!clusteredFieldIds.contains(field.getId())) {
        rewritten.add(field);
        continue;
      }
      String prefix = prefixOf(field.getId());
      if (prefix != null && !insertedParents.contains(prefix)
          && parentByPrefix.containsKey(prefix)) {
        rewritten.add(parentByPrefix.get(prefix));
        insertedParents.add(prefix);
      }
    }

    gaps.add(GapEntry.builder()
        .sheet("CaseField")
        .rowKey("(clustering)")
        .category(GapCategory.IDENTIFIER_SANITISED)
        .action(GapAction.CONDITIONAL_CODE)
        .detail("Clustered " + clusteredFieldIds.size() + " repeated fields into "
            + synthesized.size() + " @JsonUnwrapped complex type(s) to stay within the Java "
            + "field limit; the flat CCD field IDs are unchanged")
        .build());

    return new Result(rewritten, synthesized, refs);
  }

  private boolean isClusterable(FieldModel field) {
    // Label fields are not CaseData members; overlay-tagged fields go to passthrough; unwrap
    // parents from a prior pass are never re-clustered.
    return !"Label".equals(field.getFieldType())
        && (field.getOverlayTags() == null || field.getOverlayTags().isEmpty())
        && field.getUnwrapPrefix() == null;
  }

  private boolean isHomogeneous(String base, List<Instance> instances) {
    Instance reference = instances.get(0);
    Set<String> refMembers = reference.members.keySet();
    for (Instance instance : instances) {
      if (!instance.members.keySet().equals(refMembers)) {
        recordNonHomogeneous(base, "instances have differing member sets");
        return false;
      }
      for (String member : refMembers) {
        if (!membersEquivalent(reference.members.get(member), instance.members.get(member))) {
          recordNonHomogeneous(base,
              "member '" + member + "' differs in type/metadata/access across instances");
          return false;
        }
      }
    }
    return true;
  }

  private boolean membersEquivalent(FieldModel a, FieldModel b) {
    return Objects.equals(a.getJavaType(), b.getJavaType())
        && Objects.equals(a.getTypeOverride(), b.getTypeOverride())
        && Objects.equals(a.getTypeParameterOverride(), b.getTypeParameterOverride())
        && Objects.equals(a.getLabel(), b.getLabel())
        && Objects.equals(a.getHint(), b.getHint())
        && Objects.equals(a.getShowCondition(), b.getShowCondition())
        && Objects.equals(a.getRegex(), b.getRegex())
        && Objects.equals(a.getCategoryId(), b.getCategoryId())
        && Objects.equals(a.getSearchable(), b.getSearchable())
        && Objects.equals(a.getRetainHiddenValue(), b.getRetainHiddenValue())
        && Objects.equals(a.getMin(), b.getMin())
        && Objects.equals(a.getMax(), b.getMax())
        // The access-class name list carries the whole of a field's residual access (its union
        // reproduces the AuthorisationCaseField grant — see AccessClassComputer). Instances that
        // differ in it are NOT homogeneous and must not fold into one clustered member: folding
        // would drop the per-instance distinction and cluster fields that distinct access kept flat
        // (the prl caRespondent1..5 duplicate-field break). Two instances with the same residual are
        // assigned the same class-name list, so equal lists here means genuinely equal access.
        && Objects.equals(nullToEmpty(a.getAccessClassNames()), nullToEmpty(b.getAccessClassNames()));
  }

  private FieldModel toMember(String memberRemainder, FieldModel source) {
    String memberName = IdentifierSanitiser.toMemberName(deCapitalise(memberRemainder));
    // The member's own CCD id is the de-prefixed member name; @JsonProperty keeps exactness when
    // the de-capitalised remainder differs from the member name.
    return source.toBuilder()
        .id(deCapitalise(memberRemainder))
        .javaName(memberName)
        .unwrapPrefix(null)
        .build();
  }

  private void recordNonHomogeneous(String base, String reason) {
    gaps.add(GapEntry.builder()
        .sheet("CaseField")
        .rowKey(base + "*")
        .category(GapCategory.IDENTIFIER_SANITISED)
        .action(GapAction.CONDITIONAL_CODE)
        .detail("Field family '" + base + "*' left flat (not clustered): " + reason)
        .build());
  }

  private String uniqueTypeName(String base, Set<String> used) {
    String candidate = capitalise(base);
    if (!used.contains(candidate)) {
      return candidate;
    }
    int n = 2;
    while (used.contains(candidate + n)) {
      n++;
    }
    return candidate + n;
  }

  private String prefixOf(String fieldId) {
    Matcher m = NUMBERED.matcher(fieldId);
    return m.matches() ? m.group(1) + m.group(2) : null;
  }

  private static List<String> nullToEmpty(List<String> value) {
    return value == null ? List.of() : value;
  }

  private static String getter(String memberName) {
    return "get" + capitalise(memberName);
  }

  private static String capitalise(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  private static String deCapitalise(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    return Character.toLowerCase(value.charAt(0)) + value.substring(1);
  }

  /**
   * One numbered instance within a family, e.g. {@code applicant1} and its member fields.
   */
  private static final class Instance {
    private final String prefix;
    private final String base;
    // member remainder (e.g. "FirstName") -> the flat FieldModel
    private final Map<String, FieldModel> members = new LinkedHashMap<>();

    Instance(String prefix, String base) {
      this.prefix = prefix;
      this.base = base;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Instance other
          && prefix.equals(other.prefix);
    }

    @Override
    public int hashCode() {
      return prefix.hashCode();
    }

    @Override
    public String toString() {
      return prefix + "(" + base + ")";
    }
  }
}
