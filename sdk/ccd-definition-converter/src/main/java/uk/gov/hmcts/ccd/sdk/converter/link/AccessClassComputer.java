package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import uk.gov.hmcts.ccd.sdk.converter.model.AccessClassModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapEntry;

/**
 * Derives, per case field, the access grants that reproduce the AuthorisationCaseField sheet after
 * subtracting the grants the SDK's AuthorisationCaseFieldGenerator injects automatically (event,
 * caseHistory, tab/search read). Whatever a definition asks for beyond that injected baseline is the
 * field's <em>residual</em> grant map (role id to CRUD), which must be expressed on the field's
 * {@code @CCD(access = {...})}.
 *
 * <p><b>Emission policy — composition (nfdiv-style).</b> Instead of minting one class per distinct
 * residual pattern (thousands of single-use classes across the big case types), the converter
 * expresses every residual as a <em>union of named access-group classes</em>, exactly as a
 * hand-written HMCTS model does ({@code @CCD(access = {DefaultAccess.class, Applicant2Access.class})}).
 * This exploits the SDK's proven additive composition: a field's effective grant for a role is the
 * union of the permissions from every class it names (see
 * {@code AuthorisationCaseFieldGenerator.addPermissionsFromFields}, which merges each class's
 * {@code getGrants()} into the same field/role cell). Class names never appear in the emitted
 * AuthorisationCaseField — only the resolved grants do — so any partition of a residual into classes
 * whose grants union back to it round-trips byte-identically.
 *
 * <p>The decomposition is deterministic:
 *
 * <ol>
 *   <li><b>Atoms.</b> The finest grain is one class per distinct {@code (role -> CRUD)} pair that
 *       actually occurs — small and semantic ({@code CaseworkerCruAccess}, {@code CitizenRAccess}).
 *       A residual with distinct roles decomposes into one atom per role.</li>
 *   <li><b>Groups.</b> Frequently co-occurring atom-sets are mined into named bundles by a greedy
 *       frequent-itemset pass — the atom-set covering the most residual across the case type is
 *       carved out first, then the pass repeats on what remains. A group must be used by at least
 *       {@link #MIN_GROUP_FIELDS} fields and contain at least {@link #MIN_GROUP_ATOMS} atoms to earn
 *       a name. The single most-used group is named {@code DefaultAccess} for recognisability (the
 *       bundle most fields carry), the rest are named from their content.</li>
 *   <li><b>Per-field cover.</b> Each residual is covered greedily by the groups that carved it plus
 *       any leftover atoms. When a field would need more than {@link #MAX_CLASSES_PER_FIELD} classes,
 *       the whole residual falls back to one dedicated, semantically-named class (never {@code AccessNN})
 *       shared by every field with that exact residual.</li>
 * </ol>
 *
 * <p>Only classes actually referenced by some field's cover are emitted (an atom always folded into a
 * group is never minted standalone). The per-case-type class counts and per-field array distribution
 * are recorded in {@link Result#summaryNote()} for the converter report.
 *
 * <p><b>Common-prefix elision.</b> Service teams' roles are conventionally namespaced
 * ({@code caseworker-probate-caseadmin}, {@code caseworker-probate-systemupdate}, …); when a
 * simple majority of the roles participating in a case type's access classes shares a
 * hyphen-delimited prefix, that prefix is redundant in every derived name and is stripped before
 * tokenisation (see {@link #computeCommonPrefix} and {@link #stripCommonPrefix}). Concretely: the
 * longest hyphen-token prefix shared by more than {@link #COMMON_PREFIX_MIN_SHARE} of the distinct
 * roles across all residual atoms — provided at least {@link #MIN_PREFIX_ROLES} roles carry it —
 * is computed once per case type (in {@link #compute}) and removed from
 * each role before it is turned into a name token — {@code caseworker-probate-caseadmin} becomes
 * {@code Caseadmin} rather than {@code CaseworkerProbateCaseadmin}. A role that has no other
 * remainder (it <em>is</em> the prefix exactly, e.g. {@code caseworker-probate}) keeps its last
 * hyphen token ({@code Probate}) so it still yields a non-empty, distinguishing name. Roles outside
 * the prefix (e.g. {@code citizen}) are never touched. Grants themselves always key on the full,
 * unstripped role id — only the derived class <em>name</em> is affected, so this cannot change what
 * a class grants.
 */
final class AccessClassComputer {

  /**
   * The minimum share of distinct roles (participating in some case type's access classes) that
   * must share a hyphen-token prefix before it is considered case-type-wide "common" and elided
   * from derived names. A simple majority: real service teams' role namespaces (e.g. probate's
   * 11/18 = 61%) commonly fall short of a supermajority while still being the dominant, redundant
   * prefix for most roles — stripping is per-role (only prefix-carrying roles are touched, see
   * {@link #stripCommonPrefix}), so a bare majority is enough to be safe and deterministic.
   */
  static final double COMMON_PREFIX_MIN_SHARE = 0.5;

  /**
   * The minimum number of roles that must carry the candidate prefix, on top of clearing
   * {@link #COMMON_PREFIX_MIN_SHARE}, before it qualifies. Guards tiny case types (e.g. 2 of 3
   * roles sharing a prefix) where "majority" is a coincidence of a small sample rather than a
   * real namespace convention.
   */
  static final int MIN_PREFIX_ROLES = 3;

  /** A group must be used by at least this many fields to earn a name (else its atoms stand alone). */
  static final int MIN_GROUP_FIELDS = 3;

  /** A group must bundle at least this many atoms (a one-atom "group" is just an atom class). */
  static final int MIN_GROUP_ATOMS = 2;

  /**
   * A field's {@code @CCD(access)} array is capped here; beyond it the residual gets one dedicated
   * class.
   */
  static final int MAX_CLASSES_PER_FIELD = 6;

  /** Safety bound on mined groups so a pathological input cannot loop unbounded. */
  private static final int MAX_GROUPS = 1024;

  private final GapCollector gaps;

  /**
   * The hyphen-tokens of the case type's common role prefix (e.g. {@code ["caseworker",
   * "probate"]}), computed once per {@link #compute} call by {@link #computeCommonPrefix} and
   * consumed by {@link #roleToken} via {@link #stripCommonPrefix}. Empty when no prefix clears the
   * {@link #COMMON_PREFIX_MIN_SHARE} bar.
   */
  private List<String> commonPrefixTokens = List.of();

  AccessClassComputer(GapCollector gaps) {
    this.gaps = gaps;
  }

  /** One atomic grant: a single role paired with the CRUD it is granted. */
  private record Atom(String role, String crud) {
    String signature() {
      return role + '=' + crud;
    }
  }

  /**
   * A distinct residual pattern: its atom-set, the fields that carry it, plus the running cover the
   * mining pass builds up (the groups that carved it, in order) and the atoms still uncovered.
   */
  private static final class Pattern {
    private final Set<Atom> atoms;
    private final List<String> fieldIds = new ArrayList<>();
    private final List<Integer> coverGroups = new ArrayList<>();
    private final Set<Atom> uncovered;

    Pattern(Set<Atom> atoms) {
      this.atoms = atoms;
      this.uncovered = new LinkedHashSet<>(atoms);
    }

    int weight() {
      return fieldIds.size();
    }
  }

  /**
   * Computes the per-field residual grants and expresses each as a union of named access classes
   * (atoms + mined groups, with a dedicated-class fallback for over-wide residuals).
   *
   * @param fieldIds the case field IDs in declaration order
   * @param desired the AuthorisationCaseField grants: field ID to role to CRUD string
   * @param injected the SDK-injected grants: field ID to role to CRUD string
   * @return the derivation result: the emitted classes, per-field class-name lists and the summary
   */
  Result compute(
      List<String> fieldIds,
      Map<String, Map<String, String>> desired,
      Map<String, Map<String, String>> injected) {

    // 1. Residual per field, decomposed into atoms; group identical residuals into distinct patterns.
    Map<String, Pattern> patternsBySignature = new LinkedHashMap<>();
    Map<String, List<String>> fieldToClassNames = new LinkedHashMap<>();
    int fieldsWithResidual = 0;

    for (String fieldId : fieldIds) {
      Map<String, String> want = desired.getOrDefault(fieldId, Map.of());
      Map<String, String> have = injected.getOrDefault(fieldId, Map.of());
      Map<String, String> residual = residual(fieldId, want, have);
      if (residual.isEmpty()) {
        fieldToClassNames.put(fieldId, List.of());
        continue;
      }
      fieldsWithResidual++;
      Set<Atom> atoms = atoms(residual);
      Pattern pattern = patternsBySignature.computeIfAbsent(
          signature(residual), s -> new Pattern(atoms));
      pattern.fieldIds.add(fieldId);
    }

    List<Pattern> patterns = new ArrayList<>(patternsBySignature.values());

    // Every role that will appear in some derived class name, so the common-prefix computation
    // sees the same population that naming later tokenises.
    Set<String> rolesInResiduals = new LinkedHashSet<>();
    for (Pattern pattern : patterns) {
      for (Atom atom : pattern.atoms) {
        rolesInResiduals.add(atom.role());
      }
    }
    commonPrefixTokens = computeCommonPrefix(rolesInResiduals);

    // 2. Mine frequently co-occurring atom-sets into groups (greedy: carve the highest-coverage
    //    bundle out of every pattern that contains it, then repeat).
    List<Set<Atom>> groups = mineGroups(patterns);

    // 3. Per-field cover: the groups that carved a pattern, then its leftover atoms; a residual that
    //    would need more than the cap gets one dedicated class instead.
    Set<Integer> usedGroups = new LinkedHashSet<>();
    Map<Atom, Integer> atomUsage = new LinkedHashMap<>();
    // Dedicated fallbacks keyed by residual signature so identical residuals share one class.
    Map<String, Set<Atom>> dedicated = new LinkedHashMap<>();
    // Provisional cover per pattern, as a list of tokens (group index, or atom, or dedicated key).
    List<List<CoverRef>> covers = new ArrayList<>();

    for (Pattern pattern : patterns) {
      List<CoverRef> cover = new ArrayList<>();
      for (int groupIndex : pattern.coverGroups) {
        cover.add(CoverRef.group(groupIndex));
      }
      List<Atom> leftover = new ArrayList<>(pattern.uncovered);
      leftover.sort(Comparator.comparing(Atom::signature));
      for (Atom atom : leftover) {
        cover.add(CoverRef.atom(atom));
      }
      if (cover.size() > MAX_CLASSES_PER_FIELD) {
        // Too wide to read as a union — mint one dedicated class for the whole residual.
        String key = signatureOf(pattern.atoms);
        dedicated.putIfAbsent(key, pattern.atoms);
        cover = List.of(CoverRef.dedicated(key));
      } else {
        for (CoverRef ref : cover) {
          if (ref.groupIndex >= 0) {
            usedGroups.add(ref.groupIndex);
          } else if (ref.atom != null) {
            atomUsage.merge(ref.atom, pattern.weight(), Integer::sum);
          }
        }
      }
      covers.add(cover);
    }

    // 4. Assign names. DefaultAccess to the most-used group; the rest content-derived. Only referenced
    //    classes are emitted.
    Set<String> usedNames = new LinkedHashSet<>();
    Map<Integer, String> groupNames = assignGroupNames(groups, covers, usedGroups, usedNames);
    Map<Atom, String> atomNames = assignAtomNames(atomUsage.keySet(), usedNames);
    Map<String, String> dedicatedNames = assignDedicatedNames(dedicated, usedNames);

    // 5. Resolve each field's cover to concrete class names.
    for (int i = 0; i < patterns.size(); i++) {
      Pattern pattern = patterns.get(i);
      List<String> names = new ArrayList<>();
      for (CoverRef ref : covers.get(i)) {
        if (ref.groupIndex >= 0) {
          names.add(groupNames.get(ref.groupIndex));
        } else if (ref.atom != null) {
          names.add(atomNames.get(ref.atom));
        } else {
          names.add(dedicatedNames.get(ref.dedicatedKey));
        }
      }
      List<String> shared = List.copyOf(names);
      for (String fieldId : pattern.fieldIds) {
        fieldToClassNames.put(fieldId, shared);
      }
    }

    // 6. Emit only referenced classes, ordered: groups (by name), atoms (by name), fallbacks (by name).
    List<AccessClassModel> classes = new ArrayList<>();
    List<Map.Entry<String, Set<Atom>>> emittedGroups = new ArrayList<>();
    for (int groupIndex : usedGroups) {
      emittedGroups.add(Map.entry(groupNames.get(groupIndex), groups.get(groupIndex)));
    }
    emittedGroups.sort(Map.Entry.comparingByKey());
    for (Map.Entry<String, Set<Atom>> entry : emittedGroups) {
      classes.add(AccessClassModel.builder()
          .className(entry.getKey()).grants(grantsOf(entry.getValue())).build());
    }
    List<Atom> atomList = new ArrayList<>(atomUsage.keySet());
    atomList.sort(Comparator.comparing(a -> atomNames.get(a)));
    for (Atom atom : atomList) {
      classes.add(AccessClassModel.builder()
          .className(atomNames.get(atom)).grants(grantsOf(Set.of(atom))).build());
    }
    List<Map.Entry<String, Set<Atom>>> fallbacks = new ArrayList<>();
    for (Map.Entry<String, Set<Atom>> entry : dedicated.entrySet()) {
      fallbacks.add(Map.entry(dedicatedNames.get(entry.getKey()), entry.getValue()));
    }
    fallbacks.sort(Map.Entry.comparingByKey());
    for (Map.Entry<String, Set<Atom>> entry : fallbacks) {
      classes.add(AccessClassModel.builder()
          .className(entry.getKey()).grants(grantsOf(entry.getValue())).build());
    }

    String note = summaryNote(fieldToClassNames, fieldsWithResidual, emittedGroups, atomList.size(),
        fallbacks.size(), groupNames, groups, patterns, covers);
    return new Result(classes, fieldToClassNames, note);
  }

  /** One reference within a provisional per-field cover: a group index, a leftover atom, or a fallback. */
  private static final class CoverRef {
    private final int groupIndex;
    private final Atom atom;
    private final String dedicatedKey;

    private CoverRef(int groupIndex, Atom atom, String dedicatedKey) {
      this.groupIndex = groupIndex;
      this.atom = atom;
      this.dedicatedKey = dedicatedKey;
    }

    static CoverRef group(int index) {
      return new CoverRef(index, null, null);
    }

    static CoverRef atom(Atom atom) {
      return new CoverRef(-1, atom, null);
    }

    static CoverRef dedicated(String key) {
      return new CoverRef(-1, null, key);
    }
  }

  /**
   * Greedy frequent-itemset mining. Each round, considers as candidate bundles the pairwise
   * intersections of the still-uncovered atom-sets (plus each uncovered set itself), scores each by
   * the residual it would cover ({@code (|bundle| - 1) * fields-using}), carves the best qualifying
   * bundle out of every pattern that contains it, and repeats. Deterministic throughout: candidates
   * are keyed and tie-broken by signature.
   *
   * @param patterns the distinct residual patterns (their {@code uncovered}/{@code coverGroups} are mutated)
   * @return the mined groups, in mining order
   */
  private List<Set<Atom>> mineGroups(List<Pattern> patterns) {
    List<Set<Atom>> groups = new ArrayList<>();
    while (groups.size() < MAX_GROUPS) {
      Map<String, Set<Atom>> candidates = new TreeMap<>();
      for (Pattern pattern : patterns) {
        if (pattern.uncovered.size() >= MIN_GROUP_ATOMS) {
          candidates.putIfAbsent(signatureOf(pattern.uncovered), pattern.uncovered);
        }
      }
      for (int i = 0; i < patterns.size(); i++) {
        Set<Atom> a = patterns.get(i).uncovered;
        if (a.size() < MIN_GROUP_ATOMS) {
          continue;
        }
        for (int j = i + 1; j < patterns.size(); j++) {
          Set<Atom> b = patterns.get(j).uncovered;
          if (b.size() < MIN_GROUP_ATOMS) {
            continue;
          }
          Set<Atom> intersection = new LinkedHashSet<>(a);
          intersection.retainAll(b);
          if (intersection.size() >= MIN_GROUP_ATOMS) {
            candidates.putIfAbsent(signatureOf(intersection), intersection);
          }
        }
      }

      Set<Atom> best = null;
      long bestScore = -1;
      int bestSupport = 0;
      for (Set<Atom> candidate : candidates.values()) {
        int support = 0;
        for (Pattern pattern : patterns) {
          if (pattern.uncovered.containsAll(candidate)) {
            support += pattern.weight();
          }
        }
        if (support < MIN_GROUP_FIELDS) {
          continue;
        }
        long score = (long) (candidate.size() - 1) * support;
        // Higher coverage wins; ties to the larger, then higher-support, then signature (candidates
        // are already iterated in signature order, so first-wins gives the signature tie-break).
        if (score > bestScore
            || (score == bestScore && best != null && candidate.size() > best.size())) {
          best = candidate;
          bestScore = score;
          bestSupport = support;
        }
      }

      if (best == null) {
        break;
      }
      // Snapshot the chosen bundle: the candidate map holds live pattern.uncovered sets, so the
      // carve below (pattern.uncovered.removeAll(bundle)) would mutate `best` itself if it aliased
      // one — emptying it mid-loop and matching every remaining pattern (leaking foreign atoms).
      Set<Atom> bundle = new LinkedHashSet<>(best);
      int groupIndex = groups.size();
      groups.add(bundle);
      for (Pattern pattern : patterns) {
        if (pattern.uncovered.containsAll(bundle)) {
          pattern.uncovered.removeAll(bundle);
          pattern.coverGroups.add(groupIndex);
        }
      }
      // bestSupport is retained for readability of the loop; not otherwise needed here.
      assert bestSupport >= MIN_GROUP_FIELDS;
    }
    return groups;
  }

  private Map<Integer, String> assignGroupNames(
      List<Set<Atom>> groups, List<List<CoverRef>> covers, Set<Integer> usedGroups,
      Set<String> usedNames) {
    // Final usage per group (fields referencing it), to pick the most-used for DefaultAccess.
    Map<Integer, Integer> usage = new LinkedHashMap<>();
    for (List<CoverRef> cover : covers) {
      for (CoverRef ref : cover) {
        if (ref.groupIndex >= 0) {
          usage.merge(ref.groupIndex, 1, Integer::sum);
        }
      }
    }
    Map<Integer, String> names = new LinkedHashMap<>();
    if (usedGroups.isEmpty()) {
      return names;
    }
    // Most-used group -> DefaultAccess; ties break on mining order (lowest index).
    int defaultGroup = usedGroups.stream()
        .max(Comparator.<Integer>comparingInt(g -> usage.getOrDefault(g, 0))
            .thenComparing(Comparator.reverseOrder()))
        .orElseThrow();
    names.put(defaultGroup, uniqueName("DefaultAccess", usedNames));
    usedNames.add(names.get(defaultGroup));
    // The rest, in mining order, content-derived.
    for (int groupIndex : usedGroups) {
      if (groupIndex == defaultGroup) {
        continue;
      }
      String name = uniqueName(nameFor(grantsOf(groups.get(groupIndex))), usedNames);
      names.put(groupIndex, name);
      usedNames.add(name);
    }
    return names;
  }

  private Map<Atom, String> assignAtomNames(Set<Atom> atoms, Set<String> usedNames) {
    List<Atom> ordered = new ArrayList<>(atoms);
    ordered.sort(Comparator.comparing(Atom::signature));
    Map<Atom, String> names = new LinkedHashMap<>();
    for (Atom atom : ordered) {
      String name = uniqueName(atomName(atom), usedNames);
      names.put(atom, name);
      usedNames.add(name);
    }
    return names;
  }

  private Map<String, String> assignDedicatedNames(
      Map<String, Set<Atom>> dedicated, Set<String> usedNames) {
    List<Map.Entry<String, Set<Atom>>> ordered = new ArrayList<>(dedicated.entrySet());
    ordered.sort(Map.Entry.comparingByKey());
    Map<String, String> names = new LinkedHashMap<>();
    for (Map.Entry<String, Set<Atom>> entry : ordered) {
      String name = uniqueName(nameFor(grantsOf(entry.getValue())), usedNames);
      names.put(entry.getKey(), name);
      usedNames.add(name);
    }
    return names;
  }

  private Map<String, String> residual(
      String fieldId, Map<String, String> want, Map<String, String> have) {
    Map<String, String> residual = new TreeMap<>();
    Set<String> roles = new LinkedHashSet<>();
    roles.addAll(want.keySet());
    roles.addAll(have.keySet());

    for (String role : roles) {
      Set<Character> wantPerms = CrudSet.parse(want.get(role));
      Set<Character> havePerms = CrudSet.parse(have.get(role));

      if (!wantPerms.containsAll(havePerms)) {
        // The SDK injects a permission the definition does not grant; adding an access class
        // cannot remove it, so record the field/role as not derivable.
        Set<Character> extra = new LinkedHashSet<>(havePerms);
        extra.removeAll(wantPerms);
        gaps.add(GapEntry.builder()
            .sheet("AuthorisationCaseField")
            .rowKey(fieldId + "/" + role)
            .column("CRUD")
            .value(CrudSet.format(wantPerms))
            .category(GapCategory.AUTH_NOT_DERIVABLE)
            .action(GapAction.PASSTHROUGH_ROW)
            .detail("SDK injects " + CrudSet.format(extra) + " for role " + role
                + " on field " + fieldId + " which the definition does not grant; the row is"
                + " passed through so the intended grant is preserved")
            .build());
      }

      Set<Character> missing = new LinkedHashSet<>(wantPerms);
      missing.removeAll(havePerms);
      if (!missing.isEmpty()) {
        residual.put(role, CrudSet.format(missing));
      }
    }
    return residual;
  }

  /** Decomposes a residual grant map into its atoms (one per role). */
  private Set<Atom> atoms(Map<String, String> residual) {
    Set<Atom> atoms = new LinkedHashSet<>();
    for (Map.Entry<String, String> entry : new TreeMap<>(residual).entrySet()) {
      atoms.add(new Atom(entry.getKey(), entry.getValue()));
    }
    return atoms;
  }

  /** The role-to-CRUD grant map an atom-set expresses (each atom is a distinct role). */
  private Map<String, String> grantsOf(Set<Atom> atoms) {
    Map<String, String> grants = new TreeMap<>();
    for (Atom atom : atoms) {
      grants.put(atom.role(), atom.crud());
    }
    return grants;
  }

  private String signature(Map<String, String> residual) {
    Map<String, String> sorted = new TreeMap<>(residual);
    StringBuilder builder = new StringBuilder();
    for (Map.Entry<String, String> entry : sorted.entrySet()) {
      builder.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
    }
    return builder.toString();
  }

  private String signatureOf(Set<Atom> atoms) {
    Set<String> sorted = new TreeSet<>();
    for (Atom atom : atoms) {
      sorted.add(atom.signature());
    }
    return String.join(";", sorted);
  }

  private String uniqueName(String base, Set<String> used) {
    if (!used.contains(base)) {
      return base;
    }
    int suffix = 2;
    String candidate = base + suffix;
    while (used.contains(candidate)) {
      candidate = base + (++suffix);
    }
    return candidate;
  }

  /**
   * A deterministic, content-derived name for a single atom: {@code <RoleToken><CrudToken>Access}.
   *
   * @param atom the role/CRUD atom
   * @return the atom's class name (without collision suffix)
   */
  private String atomName(Atom atom) {
    String role = roleToken(atom.role());
    if (role.isEmpty()) {
      return "Access" + digest(atom.signature());
    }
    return role + crudToken(atom.crud()) + "Access";
  }

  /**
   * A deterministic, content-derived class name for a multi-role grant map. A single-role map keeps
   * the {@code <Role>Access} form; a multi-role map is named from each role's short token plus its
   * CRUD in title case, sorted by role (e.g. {@code {caseworker=CRU, citizen=R}} ->
   * {@code CaseworkerCruCitizenRAccess}). When every role in the map is granted the identical CRUD,
   * that per-role repetition is itself redundant, so the shared CRUD token is written once at the
   * end instead of after every role (e.g. {@code {caseworker=CRU, citizen=CRU}} ->
   * {@code CaseworkerCitizenCruAccess} rather than {@code CaseworkerCruCitizenCruAccess}) — safe
   * because the token stream still names the same roles in the same order, just without the
   * repeated suffix, so no information is lost and no other construction can collide with it
   * (collisions are caught by {@link #uniqueName} regardless). When that would exceed
   * {@link #MAX_SEMANTIC_NAME} characters the name is truncated to the first role's token plus a
   * role count and a short stable digest.
   *
   * @param residual the grant map (role id to CRUD)
   * @return the class name (without collision suffix)
   */
  private String nameFor(Map<String, String> residual) {
    Map<String, String> sorted = new TreeMap<>(residual);
    if (sorted.size() == 1) {
      Map.Entry<String, String> only = sorted.entrySet().iterator().next();
      String pascal = roleToken(only.getKey());
      if (!pascal.isEmpty()) {
        return pascal + "Access";
      }
      return "Access" + digest(signature(residual));
    }
    boolean uniformCrud = new HashSet<>(sorted.values()).size() == 1;
    StringBuilder full = new StringBuilder();
    for (Map.Entry<String, String> entry : sorted.entrySet()) {
      full.append(roleToken(entry.getKey()));
      if (!uniformCrud) {
        full.append(crudToken(entry.getValue()));
      }
    }
    if (uniformCrud) {
      full.append(crudToken(sorted.values().iterator().next()));
    }
    String candidate = full + "Access";
    if (candidate.length() <= MAX_SEMANTIC_NAME) {
      return candidate;
    }
    Map.Entry<String, String> first = sorted.entrySet().iterator().next();
    return roleToken(first.getKey()) + crudToken(first.getValue())
        + "Plus" + (sorted.size() - 1) + "Roles" + digest(signature(residual)) + "Access";
  }

  private static final int MAX_SEMANTIC_NAME = 70;

  /**
   * The minimum number of distinct roles a case type must have before common-prefix elision
   * applies. With a single role there is nothing to compare it against — "the whole role is the
   * prefix" would be a vacuous, misleading result — so {@link #computeCommonPrefix} declines
   * below this bound and every role keeps its full token form.
   */
  private static final int MIN_ROLES_FOR_COMMON_PREFIX = 2;

  /**
   * A role id turned into a PascalCase token (sanitised, brackets on case roles dropped, the
   * case type's common role-namespace prefix elided per {@link #stripCommonPrefix}).
   */
  private String roleToken(String role) {
    String cleaned = role.replace("[", "").replace("]", "");
    String base = IdentifierSanitiser.toMemberName(stripCommonPrefix(cleaned));
    return toPascalCase(base);
  }

  /**
   * The longest hyphen-token prefix shared by more than {@link #COMMON_PREFIX_MIN_SHARE} of the
   * given roles (and by at least {@link #MIN_PREFIX_ROLES} of them), as a token list (e.g.
   * {@code ["caseworker", "probate"]}), or empty when no prefix clears both bars or fewer than
   * {@link #MIN_ROLES_FOR_COMMON_PREFIX} distinct roles are given.
   *
   * <p>Deterministic greedy extension: at each depth, the roles still matching the prefix built
   * so far are grouped by their next hyphen token; since the groups at a given depth partition
   * those roles, at most one group can hold &gt;{@code COMMON_PREFIX_MIN_SHARE} (a strict
   * majority) of the <em>original</em> role count, so the winner (if any) is unambiguous — no
   * tie-break is needed. The prefix is extended by that token and the pass repeats on the
   * (shrinking) matching subset; it stops as soon as no token's group clears both the share and
   * minimum-count bars, or every matching role is exhausted of tokens.
   *
   * @param roles the distinct role IDs participating in some case type's access-class residuals
   * @return the shared prefix, as hyphen tokens (never null; empty when none applies)
   */
  private List<String> computeCommonPrefix(Set<String> roles) {
    List<List<String>> tokenLists = new ArrayList<>();
    for (String role : roles) {
      String cleaned = role.replace("[", "").replace("]", "");
      if (!cleaned.isEmpty()) {
        tokenLists.add(List.of(cleaned.split("-")));
      }
    }
    int total = tokenLists.size();
    if (total < MIN_ROLES_FOR_COMMON_PREFIX) {
      return List.of();
    }

    List<String> prefix = new ArrayList<>();
    List<List<String>> matching = tokenLists;
    int depth = 0;
    while (true) {
      Map<String, List<List<String>>> byNextToken = new TreeMap<>();
      for (List<String> tokens : matching) {
        if (tokens.size() > depth) {
          byNextToken.computeIfAbsent(tokens.get(depth), t -> new ArrayList<>()).add(tokens);
        }
      }
      Map.Entry<String, List<List<String>>> best = null;
      for (Map.Entry<String, List<List<String>>> entry : byNextToken.entrySet()) {
        if (best == null || entry.getValue().size() > best.getValue().size()) {
          best = entry;
        }
      }
      if (best == null
          || best.getValue().size() < MIN_PREFIX_ROLES
          || (double) best.getValue().size() / total <= COMMON_PREFIX_MIN_SHARE) {
        break;
      }
      prefix.add(best.getKey());
      matching = best.getValue();
      depth++;
    }
    return List.copyOf(prefix);
  }

  /**
   * Removes the case type's common role prefix ({@link #commonPrefixTokens}) from a role, per the
   * maintainer's rule: a role strictly inside the prefix keeps only its remainder tokens; a role
   * that IS the prefix exactly (no remainder) keeps its last token, so it still yields a
   * non-empty, distinguishing name; a role outside the prefix (including one sharing only part of
   * it) is returned unchanged.
   *
   * @param cleaned the role ID with case-role brackets already stripped
   * @return the role's naming-relevant remainder
   */
  private String stripCommonPrefix(String cleaned) {
    if (commonPrefixTokens.isEmpty()) {
      return cleaned;
    }
    String[] tokens = cleaned.split("-");
    int prefixLen = commonPrefixTokens.size();
    for (int i = 0; i < prefixLen && i < tokens.length; i++) {
      if (!tokens[i].equals(commonPrefixTokens.get(i))) {
        return cleaned;
      }
    }
    if (tokens.length < prefixLen) {
      // Only partially overlaps the prefix — not the exact-prefix case, left untouched.
      return cleaned;
    }
    if (tokens.length == prefixLen) {
      return tokens[prefixLen - 1];
    }
    StringBuilder remainder = new StringBuilder();
    for (int i = prefixLen; i < tokens.length; i++) {
      if (i > prefixLen) {
        remainder.append('-');
      }
      remainder.append(tokens[i]);
    }
    return remainder.toString();
  }

  // A CRUD string turned into a title-case token: "CRU" -> "Cru", "R" -> "R".
  private String crudToken(String crud) {
    if (crud == null || crud.isEmpty()) {
      return "";
    }
    String lower = crud.toLowerCase();
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  /** A short, stable, alphabetic digest of a signature string, for name uniqueness on truncation. */
  private String digest(String signature) {
    int hash = signature.hashCode();
    StringBuilder builder = new StringBuilder();
    long value = Integer.toUnsignedLong(hash);
    for (int i = 0; i < 6; i++) {
      builder.append((char) ('a' + (int) (value % 26)));
      value /= 26;
    }
    return toPascalCase(builder.toString());
  }

  private String toPascalCase(String value) {
    String[] parts = value.split("[^A-Za-z0-9]+");
    StringBuilder builder = new StringBuilder();
    for (String part : parts) {
      if (!part.isEmpty()) {
        builder.append(Character.toUpperCase(part.charAt(0)));
        if (part.length() > 1) {
          builder.append(part.substring(1));
        }
      }
    }
    return builder.toString();
  }

  /**
   * Builds the converter-report note: total class count broken down into groups/atoms/fallbacks, the
   * per-field array distribution (avg/max), and the mined-group table (name, roles/CRUD, usage).
   */
  private String summaryNote(
      Map<String, List<String>> fieldToClassNames, int fieldsWithResidual,
      List<Map.Entry<String, Set<Atom>>> emittedGroups, int atomCount, int fallbackCount,
      Map<Integer, String> groupNames, List<Set<Atom>> groups, List<Pattern> patterns,
      List<List<CoverRef>> covers) {
    int total = emittedGroups.size() + atomCount + fallbackCount;
    int sum = 0;
    int max = 0;
    for (List<String> names : fieldToClassNames.values()) {
      if (names.isEmpty()) {
        continue;
      }
      sum += names.size();
      max = Math.max(max, names.size());
    }
    double avg = fieldsWithResidual == 0 ? 0 : (double) sum / fieldsWithResidual;

    // Usage per group name (fields referencing it) for the mined-group table.
    Map<String, Integer> groupUsage = new LinkedHashMap<>();
    for (int i = 0; i < patterns.size(); i++) {
      int weight = patterns.get(i).weight();
      for (CoverRef ref : covers.get(i)) {
        if (ref.groupIndex >= 0) {
          groupUsage.merge(groupNames.get(ref.groupIndex), weight, Integer::sum);
        }
      }
    }

    StringBuilder sb = new StringBuilder();
    sb.append(String.format(
        "composition scheme: %d access classes (%d groups, %d atoms, %d dedicated fallbacks) "
            + "for %d fields with a residual; classes per field avg %.2f, max %d.%n",
        total, emittedGroups.size(), atomCount, fallbackCount, fieldsWithResidual, avg, max));
    if (!emittedGroups.isEmpty()) {
      sb.append(String.format("%nMined groups (name -> roles/CRUD -> fields using):%n%n"));
      sb.append("| Group | Roles/CRUD | Fields |\n|---|---|---|\n");
      for (Map.Entry<String, Set<Atom>> entry : emittedGroups) {
        String rolesCrud = grantsOf(entry.getValue()).entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .reduce((x, y) -> x + ", " + y).orElse("");
        sb.append("| ").append(entry.getKey())
            .append(" | ").append(rolesCrud)
            .append(" | ").append(groupUsage.getOrDefault(entry.getKey(), 0))
            .append(" |\n");
      }
    }
    return sb.toString();
  }

  /**
   * The derived access classes plus the class-name list to attach to each field, and a report note.
   *
   * @param accessClasses the emitted access classes (groups + atoms + dedicated fallbacks)
   * @param fieldClassNames field id to the access-class names it references (union = its residual)
   * @param summaryNote the class-count and per-field-array summary for the converter report
   */
  record Result(
      List<AccessClassModel> accessClasses,
      Map<String, List<String>> fieldClassNames,
      String summaryNote) {
  }
}
