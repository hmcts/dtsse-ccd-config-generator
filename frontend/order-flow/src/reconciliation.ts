import {
  Fragment,
  type Node as ProseMirrorNode,
} from "prosemirror-model";
import { type Transaction } from "prosemirror-state";

interface ManagedNode {
  node: ProseMirrorNode;
  position: number;
}

function managedId(node: ProseMirrorNode): string | undefined {
  const id = node.attrs.id;
  return typeof id === "string" ? id : undefined;
}

function managedChildren(node: ProseMirrorNode): ManagedNode[] {
  const children: ManagedNode[] = [];

  node.descendants((descendant, position) => {
    if (!managedId(descendant)) return true;

    children.push({ node: descendant, position });
    return false;
  });

  return children;
}

function managedChildrenById(node: ProseMirrorNode): Map<string, ManagedNode> {
  const children = new Map<string, ManagedNode>();

  for (const child of managedChildren(node)) {
    const id = managedId(child.node)!;
    if (children.has(id)) {
      throw new Error(`Duplicate managed node ID: ${id}`);
    }
    children.set(id, child);
  }

  return children;
}

function liveNode(
  transaction: Transaction,
  position: number | null,
): ProseMirrorNode {
  if (position === null) return transaction.doc;

  const node = transaction.doc.nodeAt(position);
  if (!node) throw new Error("Managed node not found");
  return node;
}

function contentPosition(position: number | null): number {
  return position === null ? 0 : position + 1;
}

function findManagedChildById(
  transaction: Transaction,
  parentPosition: number | null,
  id: string,
): ManagedNode | undefined {
  const child = managedChildrenById(
    liveNode(transaction, parentPosition),
  ).get(id);

  return child && {
    node: child.node,
    position: contentPosition(parentPosition) + child.position,
  };
}

function hasOnlyManagedChildren(node: ProseMirrorNode): boolean {
  return node.childCount > 0 &&
    node.children.every((child) => managedId(child) !== undefined);
}

function sameKeys(
  left: ReadonlyMap<string, ManagedNode>,
  right: ReadonlyMap<string, ManagedNode>,
): boolean {
  return left.size === right.size &&
    [...left.keys()].every((id) => right.has(id));
}

function sameUnmanagedContent(
  left: ProseMirrorNode,
  right: ProseMirrorNode,
  isRoot = true,
): boolean {
  if (!isRoot) {
    const leftId = managedId(left);
    const rightId = managedId(right);

    if (leftId !== undefined || rightId !== undefined) {
      return leftId === rightId;
    }
  }

  if (!left.sameMarkup(right)) return false;
  if (left.isText) return left.text === right.text;
  if (left.childCount !== right.childCount) return false;

  for (let index = 0; index < left.childCount; index++) {
    if (!sameUnmanagedContent(left.child(index), right.child(index), false)) {
      return false;
    }
  }

  return true;
}

function preserveManagedChildren(
  target: ProseMirrorNode,
  liveChildrenById: ReadonlyMap<string, ManagedNode>,
  isRoot = true,
): ProseMirrorNode {
  if (!isRoot) {
    const id = managedId(target);
    const liveChild = id === undefined ? undefined : liveChildrenById.get(id);

    if (liveChild) return liveChild.node;
  }

  if (target.isLeaf) return target;

  return target.copy(
    Fragment.fromArray(
      target.children.map((child) =>
        preserveManagedChildren(child, liveChildrenById, false)
      ),
    ),
  );
}

function insertMissingManagedNodes(
  transaction: Transaction,
  parentPosition: number | null,
  targetChildren: readonly ManagedNode[],
  insertIds: ReadonlySet<string>,
): void {
  for (let targetIndex = 0; targetIndex < targetChildren.length; targetIndex++) {
    const targetChild = targetChildren[targetIndex]!;
    const id = managedId(targetChild.node)!;
    if (!insertIds.has(id)) continue;

    let insertionPosition = contentPosition(parentPosition);

    for (
      let predecessorIndex = targetIndex - 1;
      predecessorIndex >= 0;
      predecessorIndex--
    ) {
      const predecessorId = managedId(
        targetChildren[predecessorIndex]!.node,
      )!;
      const predecessor = findManagedChildById(
        transaction,
        parentPosition,
        predecessorId,
      );
      if (!predecessor) continue;

      insertionPosition = predecessor.position + predecessor.node.nodeSize;
      break;
    }

    transaction.insert(insertionPosition, targetChild.node);
  }
}

function deleteRemovedManagedNodes(
  transaction: Transaction,
  parentPosition: number | null,
  deleteIds: ReadonlySet<string>,
): void {
  for (const id of deleteIds) {
    const child = findManagedChildById(transaction, parentPosition, id);
    if (!child) continue;

    transaction.delete(
      child.position,
      child.position + child.node.nodeSize,
    );
  }
}

function reconcileManagedChildren(
  transaction: Transaction,
  parentPosition: number | null,
  previousParent: ProseMirrorNode | undefined,
  targetParent: ProseMirrorNode,
): void {
  const liveChildren = managedChildrenById(
    liveNode(transaction, parentPosition),
  );
  const targetChildren = managedChildren(targetParent);
  const targetChildrenById = managedChildrenById(targetParent);
  const previousChildrenById = previousParent
    ? managedChildrenById(previousParent)
    : new Map<string, ManagedNode>();
  const liveIds = new Set(liveChildren.keys());
  const targetIds = new Set(targetChildrenById.keys());
  const insertIds = new Set(
    [...targetIds].filter((id) => !liveIds.has(id)),
  );
  const deleteIds = new Set(
    [...liveIds].filter((id) => !targetIds.has(id)),
  );

  insertMissingManagedNodes(
    transaction,
    parentPosition,
    targetChildren,
    insertIds,
  );
  deleteRemovedManagedNodes(transaction, parentPosition, deleteIds);

  for (const [id, targetChild] of targetChildrenById) {
    if (!liveIds.has(id)) continue;

    const liveChild = findManagedChildById(
      transaction,
      parentPosition,
      id,
    );
    if (liveChild) {
      reconcileManagedNode(
        transaction,
        liveChild.position,
        previousChildrenById.get(id)?.node,
        targetChild.node,
      );
    }
  }
}

function reconcileManagedNode(
  transaction: Transaction,
  position: number | null,
  previous: ProseMirrorNode | undefined,
  target: ProseMirrorNode,
): void {
  const live = liveNode(transaction, position);

  if (previous?.eq(target)) return;

  if (position !== null && (live.isLeaf || target.isLeaf)) {
    if (!live.eq(target)) {
      transaction.replaceWith(position, position + live.nodeSize, target);
    }
    return;
  }

  if (position !== null && live.type !== target.type) {
    transaction.replaceWith(position, position + live.nodeSize, target);
    return;
  }

  if (position !== null && !live.sameMarkup(target)) {
    transaction.setNodeMarkup(
      position,
      target.type,
      target.attrs,
      target.marks,
    );
  }

  if (hasOnlyManagedChildren(target)) {
    reconcileManagedChildren(transaction, position, previous, target);
    return;
  }

  const liveChildren = managedChildrenById(live);
  const targetChildren = managedChildrenById(target);

  if (targetChildren.size > 0 && sameKeys(liveChildren, targetChildren)) {
    if (
      position !== null &&
      (!previous || !sameUnmanagedContent(previous, target))
    ) {
      const replacement = preserveManagedChildren(target, liveChildren);

      if (!live.eq(replacement)) {
        transaction.replaceWith(
          position,
          position + live.nodeSize,
          replacement,
        );
      }
    }

    const previousChildren = previous
      ? managedChildrenById(previous)
      : new Map<string, ManagedNode>();

    for (const [id, targetChild] of targetChildren) {
      const liveChild = findManagedChildById(transaction, position, id);
      if (liveChild) {
        reconcileManagedNode(
          transaction,
          liveChild.position,
          previousChildren.get(id)?.node,
          targetChild.node,
        );
      }
    }
    return;
  }

  if (position !== null && !live.eq(target)) {
    transaction.replaceWith(position, position + live.nodeSize, target);
  }
}

export function reconcileOrderDocument(
  transaction: Transaction,
  previousTarget: ProseMirrorNode,
  target: ProseMirrorNode,
): Transaction {
  reconcileManagedNode(transaction, null, previousTarget, target);
  return transaction;
}
