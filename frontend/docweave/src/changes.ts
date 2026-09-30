import { type Node as ProseMirrorNode } from "prosemirror-model";

import { type DocWeaveSnapshot } from "./controller.js";
import { editorSchema } from "./schema.js";

/**
 * A clause is the unit the reader edits and reverts: a block at the top of
 * the document, other than a list, or an item of a numbered list.
 */
export function isClauseNode(
  node: ProseMirrorNode,
  parent: ProseMirrorNode | null,
  doc: ProseMirrorNode,
): boolean {
  return (parent === doc && node.type.name !== "ordered_list") ||
    parent?.type.name === "ordered_list";
}

/** The generated clauses of a document, by their ID. */
export function clauseNodesById(
  doc: ProseMirrorNode,
): Map<string, ProseMirrorNode> {
  const clauses = new Map<string, ProseMirrorNode>();
  doc.descendants((node, _position, parent) => {
    const id = node.attrs.id;
    if (isClauseNode(node, parent, doc) && typeof id === "string") {
      clauses.set(id, node);
    }
  });
  return clauses;
}

/** A clause's own content: a list item's nested list holds clauses of its own. */
export function ownContent(clause: ProseMirrorNode): ProseMirrorNode[] {
  return clause.children.filter((child) => child.type.name !== "ordered_list");
}

/** Whether a clause still reads as generated, leaving its nested clauses to themselves. */
export function clauseMatchesGenerated(
  node: ProseMirrorNode,
  generatedNode: ProseMirrorNode,
): boolean {
  if (!node.sameMarkup(generatedNode)) return false;
  if (node.type.name !== "list_item") return node.eq(generatedNode);

  const own = ownContent(node);
  const generatedOwn = ownContent(generatedNode);
  return own.length === generatedOwn.length &&
    own.every((child, index) => child.eq(generatedOwn[index]!));
}

/** How the reader's clause differs from the one generated for it, if at all. */
export type ClauseChange = "inserted" | "modified";

/**
 * Tells whether the reader wrote a clause, changed a generated one, or left
 * it as generated, as the editor marks it.
 */
export function clauseChange(
  node: ProseMirrorNode,
  generatedClauses: Map<string, ProseMirrorNode>,
): ClauseChange | undefined {
  const id = node.attrs.id;
  if (id === null) return "inserted";
  const generatedNode = typeof id === "string"
    ? generatedClauses.get(id)
    : undefined;
  return generatedNode && !clauseMatchesGenerated(node, generatedNode)
    ? "modified"
    : undefined;
}

/**
 * How the reader changed the document generated for them, clause by clause:
 * how many clauses they wrote, how many generated clauses they reworded, and
 * how many generated clauses are no longer in their document.
 */
export interface DocumentChanges {
  inserted: number;
  modified: number;
  removed: number;
}

export function parseSnapshot(snapshot: DocWeaveSnapshot): {
  current: ProseMirrorNode;
  generated: ProseMirrorNode;
} {
  return {
    current: editorSchema.nodeFromJSON(snapshot.current),
    generated: editorSchema.nodeFromJSON(snapshot.generated),
  };
}

/**
 * Describes how the reader changed the generated document in a snapshot, so
 * a service can tell someone reviewing it without reading the snapshot itself.
 * Needs no DOM, so it runs on a server.
 */
export function describeChanges(snapshot: DocWeaveSnapshot): DocumentChanges {
  const { current, generated } = parseSnapshot(snapshot);
  const generatedClauses = clauseNodesById(generated);
  const changes: DocumentChanges = { inserted: 0, modified: 0, removed: 0 };
  const kept = new Set<string>();

  current.descendants((node, _position, parent) => {
    if (!isClauseNode(node, parent, current)) return;
    if (typeof node.attrs.id === "string") kept.add(node.attrs.id);
    const change = clauseChange(node, generatedClauses);
    if (change) changes[change] += 1;
  });
  for (const id of generatedClauses.keys()) {
    if (!kept.has(id)) changes.removed += 1;
  }
  return changes;
}
