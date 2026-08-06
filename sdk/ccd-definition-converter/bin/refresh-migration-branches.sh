#!/usr/bin/env bash
#
# refresh-migration-branches.sh — rebuild each service's `ccd-config-generator-migration` commit from
# the CURRENT converter output, so the open migration PRs show what the converter produces today
# rather than what it produced the day they were opened.
#
# The review clones under retrofit-reports/<lane> are the single source of truth: regen-review-clones.sh
# has already written today's companion tree, passthrough resources and model patch into each. This
# script replays exactly those three artefact classes onto the branch's own base commit and commits
# the result.
#
#   1. companion sources — every UNTRACKED .java under the lane's source root in the review clone.
#      Untracked is the discriminator: the team's own model files are tracked there, generated
#      companions are not (see regen-review-clones.sh for why path-based selection is unsafe — sscs
#      declares its model INSIDE the companion root package).
#   2. passthrough resources — resources/ccd-passthrough, wholly generated, so replaced not merged.
#   3. the model annotation patch — the tracked modifications in the review clone, i.e. exactly
#      `git diff` there. Applying that diff reproduces the @CCD annotations on the team's model.
#
# Work happens in a THROWAWAY WORKTREE per lane, never in the service clone itself: no reset, no
# checkout, no branch move in the clone the user works in. Their working tree and local branch ref
# are left exactly as found.
#
# The refreshed state lands as ONE NEW COMMIT ON TOP of the branch's current tip, not as a rewrite of
# it. The tree is built from the branch's BASE (so the content is exactly today's converter output,
# with none of the previous generation's files surviving as fossils), but it is committed with the old
# tip as its parent via commit-tree. So the push is an ordinary fast-forward — no history rewrite on a
# branch that already has an open PR — and the PR's diff-since-last-review is precisely the delta
# between the two generations of converter output.
#
# The pinned SDK version IS rewritten, to --sdk-version. It used to be left alone, on the grounds that
# everything on the converter branch since the 96.1 publish was converter-only; that stopped being true
# once the branch added ConfigBuilder API the generated companions call (complexScope/complexMember —
# `git diff <old publish>..HEAD -- sdk/ccd-config-generator/src/main`). Regenerated output pinned to an
# SDK that predates the API it calls does not compile, so the two move together. Pass the version the
# publish workflow produced for the converter commit being regenerated from; omit it to keep whatever
# each branch pins today (correct only when no SDK main-source change is involved).
#
# Nothing is pushed automatically: the script prepares each commit and prints its push command,
# because these branches feed open PRs and the operator should read the diffstat first.
#
# Usage: refresh-migration-branches.sh [--sdk-version <version>] [lane ...]
#        (default lanes: the four with OPEN PRs)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONVERTER_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPORTS="${CONVERTER_DIR}/retrofit-reports"
WORKSPACE="$(cd "${CONVERTER_DIR}/../../../.." && pwd)"

# lane | service clone (rel to workspace root) | source root (rel to clone) | push remote | case type
LANES=(
  "probate-back-office|apps/probate/probate-back-office|src/main/java|origin|GrantOfRepresentation"
  "fpl-ccd-configuration|apps/fpl/fpl-ccd-configuration|service/src/main/java|fork|CARE_SUPERVISION_EPO"
  "et-ccd-callbacks|apps/et/et-ccd-callbacks|et-shared/src/main/java|fork|ET_EnglandWales"
  "prl-cos-api|apps/prl/prl-cos-api|src/main/java|fork|PRLAPPS"
  "sscs-common|apps/sscs/sscs-common|src/main/java|origin|Benefit"
  "civil-service|apps/civil/civil-service|src/main/java|fork|CIVIL"
)

BRANCH=ccd-config-generator-migration
SDK_VERSION=""

run_lane() {
  IFS='|' read -r lane clonerel srcroot remote casetype <<<"$1"
  local review="${REPORTS}/${lane}"
  local clone="${WORKSPACE}/${clonerel}"
  local wt="${WORKSPACE}/${clonerel}-refresh-wt"
  echo "########## ${lane} (${casetype})"

  [[ -d "${review}" ]] || { echo "!! no review clone at ${review}"; return 1; }
  [[ -d "${clone}" ]]  || { echo "!! no service clone at ${clone}"; return 1; }

  # The branch's base: the commit its first migration commit was built on. Derived, not assumed, so
  # this stays correct if the branch is later rebased onto a newer upstream.
  local first base tip
  first="$(git -C "${clone}" log --format=%H --grep='as Java config' -1 "${BRANCH}")"
  [[ -n "${first}" ]] || { echo "!! no migration commit found on ${lane}"; return 1; }
  base="$(git -C "${clone}" rev-parse "${first}^")"
  tip="$(git -C "${clone}" rev-parse "${BRANCH}")"
  echo "  base $(git -C "${clone}" log --oneline -1 "${base}")"
  echo "  old tip $(git -C "${clone}" log --oneline -1 "${tip}")"

  # Detached worktree at the base commit. Detached so no branch ref anywhere is touched.
  git -C "${clone}" worktree remove --force "${wt}" 2>/dev/null || true
  git -C "${clone}" worktree add -q --detach "${wt}" "${base}"

  # 1. companion sources.
  local list="${wt}/.companions.txt"
  git -C "${review}" ls-files --others --exclude-standard -- "${srcroot}" \
    | grep '\.java$' > "${list}" || true
  local n=0
  while read -r rel; do
    [[ -z "${rel}" ]] && continue
    mkdir -p "${wt}/$(dirname "${rel}")"
    cp "${review}/${rel}" "${wt}/${rel}"
    n=$((n + 1))
  done < "${list}"
  rm -f "${list}"
  echo "  companions: ${n} java files"

  # 2. passthrough resources — wholly generated, replace outright.
  rm -rf "${wt}/resources/ccd-passthrough"
  if [[ -d "${review}/resources/ccd-passthrough" ]]; then
    mkdir -p "${wt}/resources"
    cp -a "${review}/resources/ccd-passthrough" "${wt}/resources/"
    echo "  passthrough: $(find "${wt}/resources/ccd-passthrough" -type f | wc -l) files"
  fi

  # 3. the model annotation patch = the review clone's tracked modifications. Applied as a diff rather
  # than by copying files, so only the annotated hunks move and any upstream change between the review
  # baseline and this branch's base is preserved. git apply is atomic: capture rc directly, never
  # through a pipe.
  local patch="${WORKSPACE}/${clonerel}-refresh.patch"
  git -C "${review}" diff > "${patch}"
  if [[ -s "${patch}" ]]; then
    local rc=0
    git -C "${wt}" apply --3way "${patch}" || rc=$?
    if [[ ${rc} -ne 0 ]]; then
      echo "  !! model patch failed to apply (rc=${rc}); left for inspection: ${patch}"
      return 1
    fi
    echo "  model patch: $(grep -c '^+++ b/' "${patch}") files annotated"
    rm -f "${patch}"
  fi

  # The migration branches also carry the plugin/ccd wiring in the build file. That is hand-written,
  # not converter output, so take it from the old tip verbatim.
  local buildfiles
  buildfiles="$(git -C "${clone}" diff --name-only "${base}" "${tip}" \
    -- '*build.gradle' '*settings.gradle' || true)"
  if [[ -n "${buildfiles}" ]]; then
    while read -r f; do
      [[ -z "$f" ]] && continue
      git -C "${wt}" checkout "${tip}" -- "$f"
      echo "  build wiring preserved: $f"
    done <<<"${buildfiles}"
    # Then re-pin the SDK, in the files just taken from the tip. Both spellings the branches use: the
    # plugin id's `version '…'` and ET's explicit ccd-config-generator dependency coordinate. Matched on
    # the json-definition-converter- prefix the publish workflow derives from the branch ref, so nothing
    # else in the build file can be caught by it.
    if [[ -n "${SDK_VERSION}" ]]; then
      while read -r f; do
        [[ -z "$f" ]] && continue
        perl -pi -e "s/json-definition-converter-[0-9]+\.[0-9]+-[0-9a-f]{8}/${SDK_VERSION}/g" \
          "${wt}/$f"
      done <<<"${buildfiles}"
      local pinned
      pinned="$(grep -rl "${SDK_VERSION}" "${wt}" --include='*.gradle' | wc -l)"
      [[ "${pinned}" -gt 0 ]] || { echo "!! SDK re-pin matched nothing in ${lane}'s build files"; return 1; }
      echo "  SDK re-pinned to ${SDK_VERSION} in ${pinned} build file(s)"
    fi
  fi

  # The tree is what we built from the base; the parent is the branch's existing tip. commit-tree
  # separates the two, which `git commit` cannot: the content is a clean regeneration (no fossils from
  # the previous generation), while the history stays append-only so the push fast-forwards and the open
  # PR's incremental diff is exactly one generation of converter output against the other.
  git -C "${wt}" add -A
  local tree refreshed
  tree="$(git -C "${wt}" write-tree)"
  if [[ "${tree}" == "$(git -C "${clone}" rev-parse "${tip}^{tree}")" ]]; then
    echo "  already up to date with today's converter output — nothing to push"
    git -C "${clone}" worktree remove --force "${wt}"
    return 0
  fi
  refreshed="$(git -C "${wt}" commit-tree "${tree}" -p "${tip}" -F - <<EOF
Regenerate ${casetype} from the current converter

Rebuilds the generated half of this migration from ccd-definition-converter as it
stands today (branch json-definition-converter), so the PR shows what the converter
produces now rather than what it produced when the PR was opened. The tree is a clean
regeneration from this branch's base, so nothing from the previous generation survives
as a fossil; the hand-written build wiring is carried over unchanged.

The pinned SDK artifact moves with it${SDK_VERSION:+ (now ${SDK_VERSION})}: the branch adds ConfigBuilder
API the generated companions call, so the regenerated output and the SDK it resolves
against have to be the same generation.

DO NOT MERGE — this is for review of the migration's shape and fidelity, not to land.
EOF
)"
  git -C "${wt}" reset -q --hard "${refreshed}"
  echo "  committed $(git -C "${wt}" log --oneline -1)"
  git -C "${wt}" diff --shortstat "${base}" HEAD | sed 's/^/  /'
  echo "  vs old tip: $(git -C "${wt}" diff --shortstat "${tip}" HEAD)"
  # Fast-forward push: HEAD's first parent IS the current remote tip, so no --force is needed and no
  # published history is rewritten.
  echo "  PUSH:   git -C ${wt} push ${remote} HEAD:${BRANCH}"
  echo "  CLEANUP after push:   git -C ${clone} worktree remove --force ${wt}"
}

while [[ $# -gt 0 && "$1" == --* ]]; do
  case "$1" in
    --sdk-version) SDK_VERSION="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ $# -gt 0 ]]; then
  for want in "$@"; do
    for spec in "${LANES[@]}"; do
      [[ "${spec%%|*}" == "${want}" ]] && run_lane "${spec}"
    done
  done
else
  for spec in "${LANES[@]}"; do
    lane="${spec%%|*}"
    [[ "${lane}" == "sscs-common" || "${lane}" == "civil-service" ]] && continue  # PRs closed
    run_lane "${spec}"
  done
fi
