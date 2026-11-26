import { type Node as ProseMirrorNode } from "prosemirror-model";

import { isList } from "./schema.js";
import { type DocWeaveSnapshot, readSnapshot } from "./snapshot.js";

/**
 * A clause is the unit the reader edits and reverts: a block at the top of
 * the document, other than a list, or an item of a list. Only a numbered
 * list's items are numbered.
 */
export function isClauseNode(
  node: ProseMirrorNode,
  parent: ProseMirrorNode | null,
  doc: ProseMirrorNode,
): boolean {
  return (parent === doc && !isList(node)) ||
    (parent !== null && isList(parent));
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
    ? clause.children.filter((child) => !isList(child))
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

/**
 * Takes the reader's document as if Docweave had generated it, so that
 * someone else can edit it from there: every clause and list the reader wrote
 * gets a managed ID, so it can be reworded but not deleted or moved, and
 * reverting a clause restores the wording in this snapshot. Their changes are
 * no longer marked or counted. Use it on a document that will not be rendered
 * again, since a render removes the accepted clauses it does not generate.
 * Throws an InvalidSnapshotError for a snapshot that is not valid.
 */
export function acceptChanges(snapshot: DocWeaveSnapshot): DocWeaveSnapshot {
  const { current } = readSnapshot(snapshot);
  const taken = new Set<string>();
  current.descendants((node) => {
    if (typeof node.attrs.id === "string") taken.add(node.attrs.id);
  });
  let count = 0;
  const nextId = (): string => {
    let id: string;
    do id = `accepted:${++count}`; while (taken.has(id));
    return id;
  };

  const accept = (node: ProseMirrorNode, parent: ProseMirrorNode | null): ProseMirrorNode => {
    if (node.isText) return node;
    const attrs = node.attrs.id === null && (isList(node) || isClauseNode(node, parent, current))
      ? { ...node.attrs, id: nextId() }
      : node.attrs;
    const children: ProseMirrorNode[] = [];
    node.forEach((child) => children.push(accept(child, node)));
    return node.type.create(attrs, children, node.marks);
  };
  const accepted = accept(current, null).toJSON() as Record<string, unknown>;
  return { schema: "docweave-document", version: 1, current: accepted, generated: accepted };
}
