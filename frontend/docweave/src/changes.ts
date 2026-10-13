import { type Node as ProseMirrorNode } from "prosemirror-model";

import { type DocWeaveSnapshot, readSnapshot } from "./snapshot.js";

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

/**
 * A clause's own content: a list item's paragraphs, leaving its nested list,
 * whose items are clauses of their own; any other clause is a single block.
 */
export function ownContent(clause: ProseMirrorNode): ProseMirrorNode[] {
  return clause.type.name === "list_item"
    ? clause.children.filter((child) => child.type.name !== "ordered_list")
    : [clause];
}

/** Whether a clause still reads as generated, leaving its nested clauses to themselves. */
export function clauseMatchesGenerated(
  node: ProseMirrorNode,
  generatedNode: ProseMirrorNode,
): boolean {
  const own = ownContent(node);
  const generatedOwn = ownContent(generatedNode);
  return node.sameMarkup(generatedNode) &&
    own.length === generatedOwn.length &&
    own.every((child, index) => child.eq(generatedOwn[index]!));
}

/** How the reader's clause differs from the one generated for it. */
export type ClauseChange =
  | { kind: "inserted" }
  | { kind: "modified"; generated: ProseMirrorNode };

/** Said before a changed clause, since its colour says nothing to a screen reader. */
export const CHANGE_DESCRIPTIONS: Record<ClauseChange["kind"], string> = {
  inserted: "Inserted clause.",
  modified: "Modified clause.",
};

/**
 * Tells whether the reader wrote a clause, changed a generated one, or left
 * it as generated (undefined). Docweave generates every clause with an ID, so
 * a clause without one it generated, such as a heading, is the reader's.
 */
export function clauseChange(
  node: ProseMirrorNode,
  generatedClauses: Map<string, ProseMirrorNode>,
): ClauseChange | undefined {
  const id = node.attrs.id;
  const generated = typeof id === "string" ? generatedClauses.get(id) : undefined;
  if (!generated) return { kind: "inserted" };
  return clauseMatchesGenerated(node, generated) ? undefined : { kind: "modified", generated };
}

/**
 * How the reader changed the document generated for them, clause by clause:
 * how many clauses they wrote and how many generated clauses they changed,
 * in wording or formatting. It compares the document as it is now with the
 * one generated for it, so it is not a history: the editor does not let the
 * reader remove a generated clause, wording they delete within one counts as
 * modifying it, and a clause they wrote and then deleted counts as nothing.
 */
export interface DocumentChanges {
  inserted: number;
  modified: number;
}

/**
 * Describes how the reader changed the generated document in a snapshot, so
 * a service can tell someone reviewing it without reading the snapshot itself.
 * Needs no DOM, so it runs on a server. Throws an InvalidSnapshotError for a
 * snapshot that is not valid.
 */
export function describeChanges(snapshot: DocWeaveSnapshot): DocumentChanges {
  const { current, generated } = readSnapshot(snapshot);
  const generatedClauses = clauseNodesById(generated);
  const changes: DocumentChanges = { inserted: 0, modified: 0 };
  current.descendants((node, _position, parent) => {
    if (!isClauseNode(node, parent, current)) return;
    const change = clauseChange(node, generatedClauses);
    if (change) changes[change.kind] += 1;
  });
  return changes;
}
