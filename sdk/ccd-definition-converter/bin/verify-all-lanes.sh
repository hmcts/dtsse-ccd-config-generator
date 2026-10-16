#!/usr/bin/env bash
#
# verify-all-lanes.sh — run the FULL retrofit-verify pipeline (convert → patch → publish → gCC →
# diff) for each retrofit lane and print one residual count per lane.
#
# regen-review-clones.sh deliberately stops at stage 1 (patch + companion emission) because it is
# meant to be runnable offline; it therefore cannot produce residual counts. This script is the
# other half: it reuses that script's LANES table verbatim — parsed out of the file rather than
# copied — so a lane's model repo, definition dirs, overlays, env and type hints cannot drift
# between the two.
#
# ia is deliberately absent: it has no typed model to annotate (map-based CaseData), so its measure
# is the converter's generate-mode round-trip, not a retrofit lane.
#
# Usage: verify-all-lanes.sh [lane-name ...]   (default: every lane in the table)
# Exit code: 0 if every requested lane reached 0 residuals, 1 otherwise.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONVERTER_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SDK_DIR="$(cd "${CONVERTER_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${SDK_DIR}/.." && pwd)"
LANE_FILE="${SCRIPT_DIR}/regen-review-clones.sh"
OUT_DIR="${CONVERTER_DIR}/build/verify-all-lanes"

mkdir -p "${OUT_DIR}"

# Pull the lane specs straight out of regen-review-clones.sh's LANES=( ... ) array: the quoted,
# pipe-delimited lines between the array open and its closing paren, skipping comments.
mapfile -t LANES < <(awk '/^LANES=\(/{inarr=1; next} inarr && /^\)/{exit} inarr' "${LANE_FILE}" \
  | sed -e 's/^[[:space:]]*//' -e '/^#/d' -e '/^$/d' -e 's/^"//' -e 's/"$//')

[[ ${#LANES[@]} -gt 0 ]] || { echo "verify-all-lanes: parsed no lanes from ${LANE_FILE}" >&2; exit 2; }

WANTED=("$@")
wanted() {
  [[ ${#WANTED[@]} -eq 0 ]] && return 0
  local w
  for w in "${WANTED[@]}"; do [[ "$w" == "$1" ]] && return 0; done
  return 1
}

declare -a SUMMARY=()
OVERALL=0
# The first lane publishes the SDK to mavenLocal; the rest reuse it. The SDK is unchanged across
# lanes, so re-publishing per lane would only cost time.
PUBLISHED=0

for spec in "${LANES[@]}"; do
  IFS='|' read -r lane modelrepo srcroot defs casetype modelclass configpkg overlays env hints <<<"${spec}"
  wanted "${lane}" || continue

  echo "########## ${lane} (${casetype}) ##########"
  args=(--model-repo "${REPO_ROOT}/${modelrepo}"
        --model-source-root "${REPO_ROOT}/${srcroot}"
        --case-type "${casetype}"
        --model-class "${modelclass}"
        --config-package "${configpkg}")

  IFS=',' read -ra defarr <<<"${defs}"
  for d in "${defarr[@]}"; do args+=(--definition "${REPO_ROOT}/${d}"); done
  for o in ${overlays};  do args+=(--overlay-suffix "${o}"); done
  for e in ${env};       do args+=(--env "${e}"); done
  for h in ${hints};     do args+=(--type-package-hint "${h}"); done
  [[ ${PUBLISHED} -eq 1 ]] && args+=(--skip-publish)

  log="${OUT_DIR}/${lane}.log"
  status=0
  "${SCRIPT_DIR}/retrofit-verify" "${args[@]}" >"${log}" 2>&1 || status=$?
  PUBLISHED=1

  # retrofit-verify's contract: 0 = clean, 1 = residual diffs, >1 = a stage failed.
  case "${status}" in
    0) SUMMARY+=("${lane}|0|clean") ;;
    1) n="$(grep -c '^  - Sheet ' "${log}" || true)"
       SUMMARY+=("${lane}|${n}|residuals"); OVERALL=1 ;;
    *) stage="$(grep -o 'retrofit-verify \[[0-9]/5 [^]]*\]' "${log}" | tail -1)"
       SUMMARY+=("${lane}|-|STAGE FAILED ${stage:-?}"); OVERALL=1 ;;
  esac
  tail -3 "${log}"
done

echo
echo "===== retrofit lane residuals ====="
printf '%-26s %8s  %s\n' "lane" "residual" "status"
for s in "${SUMMARY[@]}"; do
  IFS='|' read -r lane n status <<<"${s}"
  printf '%-26s %8s  %s\n' "${lane}" "${n}" "${status}"
done
echo "logs: ${OUT_DIR}"
exit ${OVERALL}
