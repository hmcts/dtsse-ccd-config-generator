import { type Node as ProseMirrorNode } from "prosemirror-model";

interface ManagedNodeSnapshot {
  node: ProseMirrorNode;
  parentId: string | null;
  index: number;
}

interface ManagedDocumentSnapshot {
  nodes: ReadonlyMap<string, ManagedNodeSnapshot>;
  childrenByParent: ReadonlyMap<string | null, readonly string[]>;
}

function managedId(node: ProseMirrorNode): string | undefined {
  const id = node.attrs.id;
  return typeof id === "string" ? id : undefined;
}

function snapshotManagedDocument(
  document: ProseMirrorNode,
): ManagedDocumentSnapshot {
  const nodes = new Map<string, ManagedNodeSnapshot>();
  const childrenByParent = new Map<string | null, readonly string[]>();

  function visit(parent: ProseMirrorNode, parentId: string | null): void {
    const children: Array<{ id: string; node: ProseMirrorNode }> = [];

    parent.descendants((node) => {
      const id = managedId(node);
      if (id === undefined) return true;

      children.push({ id, node });
      return false;
    });

    childrenByParent.set(parentId, children.map((child) => child.id));

    children.forEach((child, index) => {
      if (nodes.has(child.id)) {
        throw new Error(`Duplicate managed node ID: ${child.id}`);
      }

      nodes.set(child.id, {
        node: child.node,
        parentId,
        index,
      });
      visit(child.node, child.id);
    });
  }

  visit(document, null);
  return { nodes, childrenByParent };
}

function sameValues(
  left: readonly string[],
  right: readonly string[],
): boolean {
  return left.length === right.length &&
    left.every((value, index) => value === right[index]);
}

function isManagedContainer(node: ProseMirrorNode): boolean {
  return node.type.name === "ordered_list" || node.type.name === "list_item";
}

export function assertValidGeneratedDocument(
  document: ProseMirrorNode,
): void {
  document.check();
  snapshotManagedDocument(document);
}

export function assertValidGeneratedDocumentTransition(
  previousDocument: ProseMirrorNode,
  targetDocument: ProseMirrorNode,
): void {
  previousDocument.check();
  targetDocument.check();
  const previous = snapshotManagedDocument(previousDocument);
  const target = snapshotManagedDocument(targetDocument);

  for (const [id, previousNode] of previous.nodes) {
    const targetNode = target.nodes.get(id);
    if (!targetNode) continue;

    if (previousNode.node.type !== targetNode.node.type) {
      throw new Error(`Managed node changed type: ${id}`);
    }
    if (previousNode.parentId !== targetNode.parentId) {
      throw new Error(`Managed node changed parent: ${id}`);
    }
  }

  for (const [parentId, previousChildren] of previous.childrenByParent) {
    if (parentId !== null && !target.nodes.has(parentId)) continue;

    const targetChildren = target.childrenByParent.get(parentId) ?? [];
    const previousChildSet = new Set(previousChildren);
    const targetChildSet = new Set(targetChildren);
    const retainedPreviousChildren = previousChildren.filter(
      (id) => targetChildSet.has(id),
    );
    const retainedTargetChildren = targetChildren.filter(
      (id) => previousChildSet.has(id),
    );

    if (!sameValues(retainedPreviousChildren, retainedTargetChildren)) {
      const parentDescription = parentId ?? "document root";
      throw new Error(
        `Managed children changed relative order under: ${parentDescription}`,
      );
    }

    if (sameValues(previousChildren, targetChildren)) continue;

    if (parentId === null) continue;

    const previousParent = previous.nodes.get(parentId)?.node;
    if (previousParent && !isManagedContainer(previousParent)) {
      throw new Error(
        `Managed children added or removed under non-container: ${parentId}`,
      );
    }
  }
}

export function hasSameManagedStructure(
  beforeDocument: ProseMirrorNode,
  afterDocument: ProseMirrorNode,
): boolean {
  try {
    const before = snapshotManagedDocument(beforeDocument);
    const after = snapshotManagedDocument(afterDocument);

    if (before.nodes.size !== after.nodes.size) return false;

    return [...before.nodes].every(([id, beforeNode]) => {
      const afterNode = after.nodes.get(id);
      return afterNode?.node.type === beforeNode.node.type &&
        afterNode.parentId === beforeNode.parentId &&
        afterNode.index === beforeNode.index;
    });
  } catch {
    return false;
  }
}

export function assertCurrentDocumentMatchesGenerated(
  currentDocument: ProseMirrorNode,
  generatedDocument: ProseMirrorNode,
): void {
  currentDocument.check();
  generatedDocument.check();
  if (!hasSameManagedStructure(generatedDocument, currentDocument)) {
    throw new Error(
      "Current document does not preserve the generated managed structure",
    );
  }
}
