import { type Node as ProseMirrorNode } from "prosemirror-model";

import {
  assertCurrentDocumentMatchesGenerated,
  assertValidGeneratedDocument,
} from "./invariants.js";
import { editorSchema } from "./schema.js";

export interface DocWeaveSnapshot {
  schema: "docweave-document";
  version: 1;
  current: Record<string, unknown>;
  generated: Record<string, unknown>;
}

/** Why a value is not a snapshot Docweave can use. */
export type InvalidSnapshotCode =
  | "malformed"
  | "unsupported-version"
  | "invalid-document";

export class InvalidSnapshotError extends Error {
  override readonly name = "InvalidSnapshotError";

  constructor(
    readonly code: InvalidSnapshotCode,
    message: string,
    options?: { cause?: unknown },
  ) {
    super(message, options);
  }
}

export interface ValidSnapshot {
  snapshot: DocWeaveSnapshot;
  current: ProseMirrorNode;
  generated: ProseMirrorNode;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Validates a snapshot and parses its documents, for Docweave's own use. */
export function readSnapshot(value: unknown): ValidSnapshot {
  let parsed = value;
  if (typeof value === "string") {
    try {
      parsed = JSON.parse(value);
    } catch (cause) {
      throw new InvalidSnapshotError("malformed", "Snapshot is not JSON", { cause });
    }
  }
  if (!isRecord(parsed) || !isRecord(parsed.current) || !isRecord(parsed.generated)) {
    throw new InvalidSnapshotError("malformed", "Snapshot is not a Docweave snapshot");
  }
  if (parsed.schema !== "docweave-document" || parsed.version !== 1) {
    throw new InvalidSnapshotError("unsupported-version", "Unsupported Docweave snapshot version");
  }
  try {
    const current = editorSchema.nodeFromJSON(parsed.current);
    const generated = editorSchema.nodeFromJSON(parsed.generated);
    if (current.type.name !== "doc" || generated.type.name !== "doc") {
      throw new RangeError("Snapshot documents must be documents");
    }
    assertValidGeneratedDocument(generated);
    assertCurrentDocumentMatchesGenerated(current, generated);
    return {
      snapshot: {
        schema: "docweave-document",
        version: 1,
        current: current.toJSON() as Record<string, unknown>,
        generated: generated.toJSON() as Record<string, unknown>,
      },
      current,
      generated,
    };
  } catch (cause) {
    throw new InvalidSnapshotError(
      "invalid-document",
      `Snapshot document is invalid: ${cause instanceof Error ? cause.message : String(cause)}`,
      { cause },
    );
  }
}

/**
 * Checks a snapshot that came from outside, such as a posted form field: its
 * JSON, its version, and that the reader's document keeps every generated
 * clause and fact as generated. Takes the JSON string or the parsed value and
 * returns a fresh snapshot, or throws an InvalidSnapshotError.
 */
export function parseSnapshot(value: unknown): DocWeaveSnapshot {
  return readSnapshot(value).snapshot;
}
