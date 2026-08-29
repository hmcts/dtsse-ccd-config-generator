import { type Node as ProseMirrorNode } from "prosemirror-model";
import { type Transaction } from "prosemirror-state";

function reconciliationKeys(container: ProseMirrorNode): Set<string> {
  const keys = new Set<string>();
  for (const child of container.children) {
    const id = child.attrs.id;
    if (typeof id === "string") keys.add(id);
  }
  return keys;
}

function liveContainer(
  transaction: Transaction,
  containerPosition: number | null,
): ProseMirrorNode {
  if (containerPosition === null) return transaction.doc;

  const container = transaction.doc.nodeAt(containerPosition);
  if (!container) throw new Error("Reconciliation container not found");
  return container;
}

function contentPosition(containerPosition: number | null): number {
  return containerPosition === null ? 0 : containerPosition + 1;
}

function findChildById(
  transaction: Transaction,
  containerPosition: number | null,
  id: string,
): { node: ProseMirrorNode; position: number } | undefined {
  let position = contentPosition(containerPosition);

  for (const child of liveContainer(transaction, containerPosition).children) {
    if (child.attrs.id === id) return { node: child, position };
    position += child.nodeSize;
  }

  return undefined;
}

function isContainer(node: ProseMirrorNode): boolean {
  return node.type.name === "ordered_list";
}

function replaceExistingClauses(
  transaction: Transaction,
  containerPosition: number | null,
  target: ProseMirrorNode,
  replaceKeys: ReadonlySet<string>,
): void {
  for (const targetChild of target.children) {
    const id = targetChild.attrs.id;
    if (
      typeof id !== "string" ||
      !replaceKeys.has(id) ||
      isContainer(targetChild)
    ) {
      continue;
    }

    const liveChild = findChildById(transaction, containerPosition, id);
    if (liveChild && !liveChild.node.eq(targetChild)) {
      transaction.replaceWith(
        liveChild.position,
        liveChild.position + liveChild.node.nodeSize,
        targetChild,
      );
    }
  }
}

function insertMissingNodes(
  transaction: Transaction,
  containerPosition: number | null,
  target: ProseMirrorNode,
  insertKeys: ReadonlySet<string>,
): void {
  for (let targetIndex = 0; targetIndex < target.children.length; targetIndex++) {
    const child = target.children[targetIndex]!;
    const id = child.attrs.id;
    if (typeof id !== "string" || !insertKeys.has(id)) continue;

    let insertionPosition = contentPosition(containerPosition);

    for (
      let predecessorIndex = targetIndex - 1;
      predecessorIndex >= 0;
      predecessorIndex--
    ) {
      const predecessorId = target.children[predecessorIndex]!.attrs.id;
      if (typeof predecessorId !== "string") continue;

      const predecessor = findChildById(
        transaction,
        containerPosition,
        predecessorId,
      );
      if (!predecessor) continue;

      insertionPosition = predecessor.position + predecessor.node.nodeSize;
      break;
    }

    transaction.insert(insertionPosition, child);
  }
}

function deleteRemovedNodes(
  transaction: Transaction,
  containerPosition: number | null,
  deleteKeys: ReadonlySet<string>,
): void {
  for (const id of deleteKeys) {
    const liveChild = findChildById(transaction, containerPosition, id);
    if (!liveChild) continue;

    transaction.delete(
      liveChild.position,
      liveChild.position + liveChild.node.nodeSize,
    );
  }
}

function reconcileChildContainers(
  transaction: Transaction,
  containerPosition: number | null,
  target: ProseMirrorNode,
): void {
  for (const targetChild of target.children) {
    const id = targetChild.attrs.id;
    if (typeof id !== "string" || !isContainer(targetChild)) continue;

    const liveChild = findChildById(transaction, containerPosition, id);
    if (liveChild) {
      if (!liveChild.node.sameMarkup(targetChild)) {
        transaction.setNodeMarkup(
          liveChild.position,
          targetChild.type,
          targetChild.attrs,
          targetChild.marks,
        );
      }

      reconcileContainer(transaction, liveChild.position, targetChild);
    }
  }
}

function reconcileContainer(
  transaction: Transaction,
  containerPosition: number | null,
  target: ProseMirrorNode,
): void {
  const oldKeys = reconciliationKeys(
    liveContainer(transaction, containerPosition),
  );
  const newKeys = reconciliationKeys(target);

  const replaceKeys = new Set(
    [...oldKeys].filter((key) => newKeys.has(key)),
  );
  const insertKeys = new Set(
    [...newKeys].filter((key) => !oldKeys.has(key)),
  );
  const deleteKeys = new Set(
    [...oldKeys].filter((key) => !newKeys.has(key)),
  );

  replaceExistingClauses(
    transaction,
    containerPosition,
    target,
    replaceKeys,
  );
  insertMissingNodes(transaction, containerPosition, target, insertKeys);
  deleteRemovedNodes(transaction, containerPosition, deleteKeys);
  reconcileChildContainers(transaction, containerPosition, target);
}

export function reconcileOrderDocument(
  transaction: Transaction,
  target: ProseMirrorNode,
): Transaction {
  reconcileContainer(transaction, null, target);
  return transaction;
}
