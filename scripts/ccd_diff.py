#!/usr/bin/env python3
"""Compare CCD config outputs and surface semantic differences."""

import argparse
import json
import sys
from collections import Counter
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple
import textwrap

ALWAYS_IGNORED = {"Comment", "DisplayOrder", "FieldDisplayOrder", "ElementLabel"}
CONDITIONAL_REMOVALS = {
    ("SecurityClassification", "Public"),
    ("EventElementLabel", " "),
    ("PageLabel", " "),
    ("ShowSummary", "N"),
    ("Publish", "N"),
    ("ShowEventNotes", "N"),
    ("ShowSummaryChangeOption", "N"),
    ("ShowSummaryChangeOption", "No"),
}
MAX_PRINTED_ITEMS = 20


@dataclass
class FileDiff:
    path: Path
    missing: List[str]
    unexpected: List[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Compare two CCD config directories")
    parser.add_argument("base", type=Path, help="Path to config generated from base branch")
    parser.add_argument("head", type=Path, help="Path to config generated from PR branch")
    parser.add_argument(
        "--ignore-field",
        action="append",
        default=[],
        dest="ignored_fields",
        help="Field name to ignore during comparison (can be specified multiple times)",
    )
    return parser.parse_args()


def ensure_directory(path: Path, label: str) -> None:
    if not path.exists() or not path.is_dir():
        raise FileNotFoundError(f"{label} directory not found: {path}")


def discover_files(root: Path) -> Dict[Path, Path]:
    files: Dict[Path, Path] = {}
    for file_path in root.rglob("*.json"):
        rel = file_path.relative_to(root)
        files[rel] = file_path
    return files


def load_entries(path: Path) -> List[dict]:
    try:
        with path.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Failed to parse JSON from {path}: {exc}") from exc

    if isinstance(data, list):
        items = data
    elif isinstance(data, dict):
        items = [data]
    else:
        raise TypeError(f"Unsupported JSON structure in {path}: {type(data)}")

    entries: List[dict] = []
    for item in items:
        if isinstance(item, dict):
            entries.append(dict(item))
        else:
            entries.append({"__value": item})
    return entries


def normalize_entry(entry: dict, ignore_fields: Iterable[str], strip_id: bool) -> dict:
    result: dict = {}
    ignore_set = set(ignore_fields)
    for key, value in entry.items():
        if key in ALWAYS_IGNORED or key in ignore_set:
            continue
        if (key, value) in CONDITIONAL_REMOVALS:
            continue
        result[key] = value
    if strip_id:
        result.pop("ID", None)
    return result


def canonicalise(entry: dict) -> str:
    return json.dumps(entry, sort_keys=True, ensure_ascii=True, separators=(",", ":"))


def build_index(
    entries: List[dict],
    ignore_fields: Iterable[str],
    strip_id: bool,
) -> Tuple[Counter, Dict[str, dict]]:
    counter: Counter = Counter()
    samples: Dict[str, dict] = {}
    for entry in entries:
        normalised = normalize_entry(entry, ignore_fields, strip_id)
        key = canonicalise(normalised)
        counter[key] += 1
        if key not in samples:
            samples[key] = normalised
    return counter, samples


def expand(counter: Counter) -> List[str]:
    expanded: List[str] = []
    for key, count in counter.items():
        expanded.extend([key] * count)
    expanded.sort()
    return expanded


def find_best_match(entry: dict, candidates: Dict[str, dict]) -> Optional[Tuple[float, dict]]:
    if not candidates:
        return None
    entry_str = canonicalise(entry)
    best_ratio = 0.0
    best_entry: Optional[dict] = None
    for candidate in candidates.values():
        candidate_str = canonicalise(candidate)
        ratio = SequenceMatcher(None, entry_str, candidate_str).ratio()
        if ratio > best_ratio:
            best_ratio = ratio
            best_entry = candidate
    if best_entry is None or best_ratio <= 0.0 or best_ratio >= 0.999:
        return None
    return best_ratio, best_entry


def indent(text: str, prefix: str) -> str:
    return textwrap.indent(text, prefix)


def describe_entries(
    label: str,
    keys: List[str],
    samples: Dict[str, dict],
    opposite_samples: Dict[str, dict],
) -> None:
    if not keys:
        return
    print(f"  {label} ({len(keys)}):")
    displayed = keys[:MAX_PRINTED_ITEMS]
    for index, key in enumerate(displayed, 1):
        entry = samples[key]
        pretty = json.dumps(entry, indent=2, sort_keys=True, ensure_ascii=False)
        print(f"    {index})")
        print(indent(pretty, "      "))
        match = find_best_match(entry, opposite_samples)
        if match:
            ratio, candidate = match
            print(f"      closest match {ratio * 100:.1f}%:")
            candidate_pretty = json.dumps(candidate, indent=2, sort_keys=True, ensure_ascii=False)
            print(indent(candidate_pretty, "        "))
    remaining = len(keys) - len(displayed)
    if remaining > 0:
        print(f"    ... {remaining} more not shown")


def compare_file(
    rel_path: Path,
    base_file: Path,
    head_file: Path,
    ignore_fields: Iterable[str],
) -> Optional[FileDiff]:
    if "nonprod" in rel_path.name.lower():
        return None

    strip_id = "CaseEventToComplexTypes" in rel_path.as_posix()

    base_entries = load_entries(base_file)
    head_entries = load_entries(head_file)

    base_counter, base_samples = build_index(base_entries, ignore_fields, strip_id)
    head_counter, head_samples = build_index(head_entries, ignore_fields, strip_id)

    missing_keys = expand(base_counter - head_counter)
    unexpected_keys = expand(head_counter - base_counter)

    if not missing_keys and not unexpected_keys:
        return None

    diff = FileDiff(path=rel_path, missing=missing_keys, unexpected=unexpected_keys)
    print(f"Differences found in {rel_path.as_posix()}")
    describe_entries("Missing entries", missing_keys, base_samples, head_samples)
    describe_entries("Unexpected entries", unexpected_keys, head_samples, base_samples)
    print()
    return diff


def main() -> int:
    args = parse_args()

    base_dir = args.base.resolve()
    head_dir = args.head.resolve()

    try:
        ensure_directory(base_dir, "Base")
        ensure_directory(head_dir, "Head")
    except FileNotFoundError as exc:
        print(exc, file=sys.stderr)
        return 2

    base_files = discover_files(base_dir)
    head_files = discover_files(head_dir)

    missing_files = sorted(set(base_files) - set(head_files))
    new_files = sorted(set(head_files) - set(base_files))

    differences: List[FileDiff] = []

    if missing_files:
        differences.append(FileDiff(Path("__missing__"), [], []))
        print("Files only in base:")
        for rel in missing_files[:MAX_PRINTED_ITEMS]:
            print(f"  {rel.as_posix()}")
        if len(missing_files) > MAX_PRINTED_ITEMS:
            print(f"  ... {len(missing_files) - MAX_PRINTED_ITEMS} more not shown")
        print()

    if new_files:
        differences.append(FileDiff(Path("__new__"), [], []))
        print("Files only in head:")
        for rel in new_files[:MAX_PRINTED_ITEMS]:
            print(f"  {rel.as_posix()}")
        if len(new_files) > MAX_PRINTED_ITEMS:
            print(f"  ... {len(new_files) - MAX_PRINTED_ITEMS} more not shown")
        print()

    shared_files = sorted(set(base_files) & set(head_files))

    for rel in shared_files:
        base_file = base_files[rel]
        head_file = head_files[rel]
        diff = compare_file(rel, base_file, head_file, args.ignored_fields)
        if diff:
            differences.append(diff)

    if differences:
        print("Semantic differences detected in CCD configuration outputs.")
        return 1

    print("No semantic differences detected between CCD configuration outputs.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
