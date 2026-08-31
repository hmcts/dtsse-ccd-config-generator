import { type Node as ProseMirrorNode } from "prosemirror-model";
import { type Transaction } from "prosemirror-state";

import {
  createChangePlan,
  managedChildren,
  type NodeChange,
} from "./reconciliation-plan.js";

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
): number {
  const targetChildren = managedChildren(targetParent);
  const targetIndex = targetChildren.findIndex((child) => child.id === id);
  if (targetIndex === -1) throw new Error(`Target node missing: ${id}`);

  if (targetIndex === 0) return contentPosition(parentPosition);

  const predecessor = requireManagedNode(
    transaction,
    targetChildren[targetIndex - 1]!.id,
  );
  return predecessor.position + predecessor.node.nodeSize;
}

function isManagedContainer(node: ProseMirrorNode): boolean {
  return node.type.name === "ordered_list" || node.type.name === "list_item";
}

function findDirectNestedList(
  transaction: Transaction,
  parentPosition: number,
): PositionedNode | undefined {
  const parent = transaction.doc.nodeAt(parentPosition);
  if (!parent) throw new Error("Managed list item missing");

  let offset = 0;
  for (const child of parent.children) {
    if (child.type.name === "ordered_list") {
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
  const existing = findDirectNestedList(transaction, parentPosition);
  if (!existing) {
    const parent = transaction.doc.nodeAt(parentPosition);
    if (!parent) throw new Error("Managed list item missing");
    transaction.insert(parentPosition + parent.nodeSize - 1, target);
    return;
  }

  transaction.setNodeMarkup(
    existing.position,
    target.type,
    target.attrs,
    target.marks,
  );
  for (const child of managedChildren(target)) {
    const position = insertionPosition(
      transaction,
      existing.position,
      target,
      child.id,
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

function reconcileModified(
  transaction: Transaction,
  change: ModifiedNodeChange,
): void {
  const live = requireManagedNode(transaction, change.id);

  if (isManagedContainer(change.target)) {
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
    );
    return;
  }

  if (change.target.isLeaf || live.node.eq(change.previous)) {
    transaction.replaceWith(
      live.position,
      live.position + live.node.nodeSize,
      change.target,
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
): void {
  const changes = createChangePlan(previousParent, targetParent);

  for (const change of changes) {
    switch (change.kind) {
      case "added": {
        if (
          targetParent.type.name === "list_item" &&
          change.target.type.name === "ordered_list" &&
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
          change.previous.type.name === "ordered_list"
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

export function reconcileOrderDocument(
  transaction: Transaction,
  previousTarget: ProseMirrorNode,
  target: ProseMirrorNode,
): Transaction {
  reconcileParent(transaction, null, previousTarget, target);
  return transaction;
}
