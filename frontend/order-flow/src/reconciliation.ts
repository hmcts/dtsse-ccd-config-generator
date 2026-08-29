import { type Node as ProseMirrorNode } from "prosemirror-model";
import { type Transaction } from "prosemirror-state";

function reconciliationKeys(document: ProseMirrorNode): Set<string> {
  const keys = new Set<string>();
  for (const child of document.children) {
    const id = child.attrs.id;
    if (typeof id === "string") keys.add(id);
  }
  return keys;
}

function replaceExistingClauses(
  transaction: Transaction,
  target: ProseMirrorNode,
  replaceKeys: ReadonlySet<string>,
): void {
  for (const targetChild of target.children) {
    const id = targetChild.attrs.id;
    if (
      typeof id !== "string" ||
      !replaceKeys.has(id) ||
      targetChild.type.name === "ordered_list"
    ) {
      continue;
    }

    let livePosition = 0;

    for (const liveChild of transaction.doc.children) {
      if (liveChild.attrs.id === id) {
        if (!liveChild.eq(targetChild)) {
          transaction.replaceWith(
            livePosition,
            livePosition + liveChild.nodeSize,
            targetChild,
          );
        }
        break;
      }

      livePosition += liveChild.nodeSize;
    }
  }
}

function insertMissingClauses(
  transaction: Transaction,
  target: ProseMirrorNode,
  insertKeys: ReadonlySet<string>,
): void {
  for (let targetIndex = 0; targetIndex < target.children.length; targetIndex++) {
    const child = target.children[targetIndex]!;
    const id = child.attrs.id;
    if (
      typeof id !== "string" ||
      !insertKeys.has(id)
    ) {
      continue;
    }

    let insertionPosition = 0;
    let predecessorFound = false;

    for (
      let predecessorIndex = targetIndex - 1;
      predecessorIndex >= 0;
      predecessorIndex--
    ) {
      const predecessor = target.children[predecessorIndex]!;
      const predecessorId = predecessor.attrs.id;
      let livePosition = 0;

      for (const liveChild of transaction.doc.children) {
        if (
          typeof predecessorId === "string" &&
          liveChild.attrs.id === predecessorId
        ) {
          insertionPosition = livePosition + liveChild.nodeSize;
          predecessorFound = true;
          break;
        }

        livePosition += liveChild.nodeSize;
      }

      if (predecessorFound) break;
    }

    transaction.insert(insertionPosition, child);
  }
}

function deleteRemovedClauses(
  transaction: Transaction,
  deleteKeys: ReadonlySet<string>,
): void {
  for (const id of deleteKeys) {
    let livePosition = 0;

    for (const liveChild of transaction.doc.children) {
      if (liveChild.attrs.id === id) {
        transaction.delete(
          livePosition,
          livePosition + liveChild.nodeSize,
        );
        break;
      }

      livePosition += liveChild.nodeSize;
    }
  }
}

export function reconcileOrderDocument(
  transaction: Transaction,
  target: ProseMirrorNode,
): Transaction {
  const oldKeys = reconciliationKeys(transaction.doc);
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

  replaceExistingClauses(transaction, target, replaceKeys);
  insertMissingClauses(transaction, target, insertKeys);
  deleteRemovedClauses(transaction, deleteKeys);

  return transaction;
}
