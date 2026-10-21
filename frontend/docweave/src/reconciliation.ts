import { Fragment, type Node as ProseMirrorNode } from "prosemirror-model";
import { type Transaction } from "prosemirror-state";

import { assertValidGeneratedDocumentTransition } from "./invariants.js";
import {
  createChangePlan,
  managedChildren,
  type NodeChange,
} from "./reconciliation-plan.js";
import { editorSchema, isList } from "./schema.js";

interface PositionedNode {
  node: ProseMirrorNode;
  position: number;
}

type ModifiedNodeChange = Extract<NodeChange, { kind: "modified" }>;

function contentPosition(position: number | null): number {
  return position === null ? 0 : position + 1;
}

function requireManagedNode(
  transaction: Transaction,
  id: string,
): PositionedNode {
  let result: PositionedNode | undefined;

  transaction.doc.descendants((node, position) => {
    if (node.attrs.id === id) {
      result = { node, position };
    }
    return true;
  });

  if (!result) throw new Error(`Managed node missing: ${id}`);
  return result;
}

function insertionPosition(
  transaction: Transaction,
  parentPosition: number | null,
  targetParent: ProseMirrorNode,
  id: string,
  { pastReaderContent = true }: { pastReaderContent?: boolean } = {},
): number {
  const targetChildren = managedChildren(targetParent);
  const targetIndex = targetChildren.findIndex((child) => child.id === id);
  if (targetIndex === -1) throw new Error(`Target node missing: ${id}`);

  if (targetIndex === 0) return contentPosition(parentPosition);

  const predecessor = requireManagedNode(
    transaction,
    targetChildren[targetIndex - 1]!.id,
  );
  const end = predecessor.position + predecessor.node.nodeSize;
  return pastReaderContent ? afterReaderContent(transaction, end) : end;
}

function containsManagedNode(node: ProseMirrorNode): boolean {
  return typeof node.attrs.id === "string" || managedChildren(node).length > 0;
}

/**
 * Past the reader's own content that follows a position among its siblings:
 * what the reader added after a generated node stays with it, so a node the
 * next render adds goes after it rather than between the two.
 */
function afterReaderContent(transaction: Transaction, position: number): number {
  const $position = transaction.doc.resolve(position);
  const parent = $position.parent;
  let index = $position.index();
  let end = position;
  while (index < parent.childCount && !containsManagedNode(parent.child(index))) {
    end += parent.child(index).nodeSize;
    index++;
  }
  return end;
}

function isManagedContainer(node: ProseMirrorNode): boolean {
  return isList(node) || node.type.name === "list_item";
}

/** The item's first nested list of the type, which a generated one adopts. */
function findDirectNestedList(
  transaction: Transaction,
  parentPosition: number,
  type: ProseMirrorNode["type"],
): PositionedNode | undefined {
  const parent = transaction.doc.nodeAt(parentPosition);
  if (!parent) throw new Error("Managed list item missing");

  let offset = 0;
  for (const child of parent.children) {
    if (child.type === type) {
      return { node: child, position: parentPosition + 1 + offset };
    }
    offset += child.nodeSize;
  }
  return undefined;
}

function addNestedList(
  transaction: Transaction,
  parentPosition: number,
  target: ProseMirrorNode,
): void {
  const existing = findDirectNestedList(transaction, parentPosition, target.type);
  if (!existing) {
    const parent = transaction.doc.nodeAt(parentPosition);
    if (!parent) throw new Error("Managed list item missing");
    let wordingEnd = parentPosition + parent.nodeSize - 1;
    const readerList = parent.lastChild!;
    // An item holds one nested list, so bullets the reader gave it go on as a
    // clause of their own after it, split off in place so undo still finds them.
    if (isList(readerList)) {
      wordingEnd -= readerList.nodeSize;
      transaction.insert(wordingEnd, editorSchema.nodes.paragraph!.create());
      transaction.split(wordingEnd, 1, [{ type: editorSchema.nodes.list_item!, attrs: { id: null } }]);
    }
    transaction.insert(wordingEnd, target);
    return;
  }

  transaction.setNodeMarkup(
    existing.position,
    target.type,
    target.attrs,
    target.marks,
  );
  for (const child of managedChildren(target)) {
    // The generated subclauses go in together ahead of the reader's, as they
    // are all new: none of the reader's content follows one of them yet.
    const position = insertionPosition(
      transaction,
      existing.position,
      target,
      child.id,
      { pastReaderContent: false },
    );
    transaction.insert(position, child.node);
  }
}

function removeNestedList(
  transaction: Transaction,
  previous: ProseMirrorNode,
): void {
  const liveList = requireManagedNode(transaction, previous.attrs.id as string);
  const hasUserAuthoredChildren = liveList.node.children.some(
    (child) => typeof child.attrs.id !== "string",
  );

  if (!hasUserAuthoredChildren) {
    transaction.delete(
      liveList.position,
      liveList.position + liveList.node.nodeSize,
    );
    return;
  }

  for (const child of managedChildren(previous)) {
    const liveChild = requireManagedNode(transaction, child.id);
    transaction.delete(
      liveChild.position,
      liveChild.position + liveChild.node.nodeSize,
    );
  }

  const remainingList = requireManagedNode(
    transaction,
    previous.attrs.id as string,
  );
  transaction.setNodeMarkup(
    remainingList.position,
    remainingList.node.type,
    { ...remainingList.node.attrs, id: null },
    remainingList.node.marks,
  );
}

function reconcileNestedModifications(
  transaction: Transaction,
  previousParent: ProseMirrorNode,
  targetParent: ProseMirrorNode,
): void {
  for (const change of createChangePlan(previousParent, targetParent)) {
    if (change.kind !== "modified") continue;
    reconcileModified(transaction, change);
  }
}
function directListItemContent(
  node: ProseMirrorNode,
): readonly ProseMirrorNode[] {
  return node.children.filter((child) => !isList(child));
}

function referenceContentNodeEqual(
  left: ProseMirrorNode,
  right: ProseMirrorNode,
): boolean {
  if (
    left.type.name === "generated_text" ||
    right.type.name === "generated_text"
  ) {
    return left.type === right.type && left.attrs.id === right.attrs.id;
  }

  if (!left.sameMarkup(right) || left.childCount !== right.childCount) {
    return false;
  }
  if (left.isText) return left.text === right.text;

  return left.children.every((child, index) =>
    referenceContentNodeEqual(child, right.child(index))
  );
}

function referenceClauseContentEqual(
  previous: ProseMirrorNode,
  target: ProseMirrorNode,
): boolean {
  const previousContent = previous.type.name === "list_item"
    ? directListItemContent(previous)
    : previous.children;
  const targetContent = target.type.name === "list_item"
    ? directListItemContent(target)
    : target.children;

  return previousContent.length === targetContent.length &&
    previousContent.every((node, index) =>
      referenceContentNodeEqual(node, targetContent[index]!)
    );
}

function reconcileListItemContent(
  transaction: Transaction,
  live: PositionedNode,
  previous: ProseMirrorNode,
  target: ProseMirrorNode,
): boolean {
  const liveContent = directListItemContent(live.node);
  const targetContent = directListItemContent(target);

  if (referenceClauseContentEqual(previous, target)) return false;

  const liveContentSize = liveContent.reduce(
    (size, node) => size + node.nodeSize,
    0,
  );
  transaction.replaceWith(
    live.position + 1,
    live.position + 1 + liveContentSize,
    Fragment.fromArray(targetContent),
  );
  return true;
}

function isNestedListChange(change: NodeChange): boolean {
  return isList(change.kind === "removed" ? change.previous : change.target);
}

function reconcileModified(
  transaction: Transaction,
  change: ModifiedNodeChange,
): void {
  const live = requireManagedNode(transaction, change.id);

  if (change.target.isLeaf) {
    // A new value for a fact keeps any formatting the reader gave it.
    transaction.setNodeMarkup(
      live.position,
      change.target.type,
      change.target.attrs,
    );
    return;
  }

  if (
    live.node.eq(change.previous) ||
    (change.target.type.name === "paragraph" &&
      !referenceClauseContentEqual(change.previous, change.target))
  ) {
    transaction.replaceWith(
      live.position,
      live.position + live.node.nodeSize,
      change.target,
    );
    return;
  }

  if (isManagedContainer(change.target)) {
    let replacedListItemContent = false;
    if (change.target.type.name === "list_item") {
      replacedListItemContent = reconcileListItemContent(
        transaction,
        live,
        change.previous,
        change.target,
      );
    }
    if (!live.node.sameMarkup(change.target)) {
      transaction.setNodeMarkup(
        live.position,
        change.target.type,
        change.target.attrs,
        change.target.marks,
      );
    }
    reconcileParent(
      transaction,
      live.position,
      change.previous,
      change.target,
      replacedListItemContent ? isNestedListChange : undefined,
    );
    return;
  }

  reconcileNestedModifications(
    transaction,
    change.previous,
    change.target,
  );
}

function reconcileParent(
  transaction: Transaction,
  parentPosition: number | null,
  previousParent: ProseMirrorNode,
  targetParent: ProseMirrorNode,
  includeChange: (change: NodeChange) => boolean = () => true,
): void {
  const changes = createChangePlan(previousParent, targetParent);

  for (const change of changes) {
    if (!includeChange(change)) continue;
    switch (change.kind) {
      case "added": {
        if (
          targetParent.type.name === "list_item" &&
          isList(change.target) &&
          parentPosition !== null
        ) {
          addNestedList(transaction, parentPosition, change.target);
          break;
        }
        const position = insertionPosition(
          transaction,
          parentPosition,
          targetParent,
          change.id,
        );
        transaction.insert(position, change.target);
        break;
      }
      case "modified":
        reconcileModified(transaction, change);
        break;
      case "removed": {
        if (
          previousParent.type.name === "list_item" &&
          isList(change.previous)
        ) {
          removeNestedList(transaction, change.previous);
          break;
        }
        const live = requireManagedNode(transaction, change.id);
        transaction.delete(
          live.position,
          live.position + live.node.nodeSize,
        );
        break;
      }
    }
  }
}

export function reconcileDocument(
  transaction: Transaction,
  previousTarget: ProseMirrorNode,
  target: ProseMirrorNode,
): Transaction {
  assertValidGeneratedDocumentTransition(previousTarget, target);
  reconcileParent(transaction, null, previousTarget, target);
  return transaction;
}
