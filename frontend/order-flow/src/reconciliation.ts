import {
  Fragment,
  type Node as ProseMirrorNode,
} from "prosemirror-model";

function reconciliationKey(
  parent: ProseMirrorNode,
  child: ProseMirrorNode,
): string | null {
  const clauseId: unknown = child.attrs.clause_id;
  if (typeof clauseId === "string") return `clause:${clauseId}`;

  if (
    parent.type.name === "doc" &&
    child.type.name === "ordered_list"
  ) {
    return "structure:ordered-list";
  }

  return null;
}

export function reconcileOrderDocument(
  liveContainer: ProseMirrorNode,
  targetContainer: ProseMirrorNode,
): ProseMirrorNode {
  const targetChildren = targetContainer.content.content;
  const targetIndexes = new Map<string, number>();

  targetChildren.forEach((child, index) => {
    const key = reconciliationKey(targetContainer, child);
    if (key === null) throw new Error("Every target clause must have an ID");
    if (targetIndexes.has(key)) throw new Error(`Duplicate target key: ${key}`);
    targetIndexes.set(key, index);
  });

  const reconciled: ProseMirrorNode[] = [];
  const userContent: ProseMirrorNode[] = [];
  let targetIndex = 0;

  liveContainer.forEach((liveChild) => {
    const key = reconciliationKey(liveContainer, liveChild);
    if (key === null) {
      userContent.push(liveChild);
      return;
    }

    const matchingIndex = targetIndexes.get(key);
    if (matchingIndex === undefined) return;
    if (matchingIndex < targetIndex) {
      throw new Error("Reordering existing clauses is not supported");
    }

    while (targetIndex < matchingIndex) {
      reconciled.push(targetChildren[targetIndex++]!);
    }

    reconciled.push(...userContent);
    userContent.length = 0;
    const targetChild = targetChildren[targetIndex++]!;
    reconciled.push(
      targetChild.type.name === "ordered_list"
        ? reconcileOrderDocument(liveChild, targetChild)
        : targetChild,
    );
  });

  reconciled.push(...targetChildren.slice(targetIndex), ...userContent);
  return targetContainer.copy(Fragment.fromArray(reconciled));
}
