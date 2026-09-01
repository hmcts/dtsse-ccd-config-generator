import { type Node as ProseMirrorNode } from "prosemirror-model";

export interface ManagedNode {
  id: string;
  node: ProseMirrorNode;
}

export type NodeChange =
  | {
    kind: "added";
    id: string;
    target: ProseMirrorNode;
  }
  | {
    kind: "removed";
    id: string;
    previous: ProseMirrorNode;
  }
  | {
    kind: "modified";
    id: string;
    previous: ProseMirrorNode;
    target: ProseMirrorNode;
  };

function managedId(node: ProseMirrorNode): string | undefined {
  const id = node.attrs.id;
  return typeof id === "string" ? id : undefined;
}

export function managedChildren(node: ProseMirrorNode): ManagedNode[] {
  const children: ManagedNode[] = [];

  node.descendants((child) => {
    const id = managedId(child);
    if (id === undefined) return true;

    children.push({ id, node: child });
    return false;
  });

  return children;
}

function managedChildrenById(
  node: ProseMirrorNode,
): Map<string, ProseMirrorNode> {
  const children = new Map<string, ProseMirrorNode>();

  for (const child of managedChildren(node)) {
    if (children.has(child.id)) {
      throw new Error(`Duplicate managed node ID: ${child.id}`);
    }
    children.set(child.id, child.node);
  }

  return children;
}

export function createChangePlan(
  previousParent: ProseMirrorNode,
  targetParent: ProseMirrorNode,
): readonly NodeChange[] {
  const previous = managedChildrenById(previousParent);
  const target = managedChildrenById(targetParent);
  const changes: NodeChange[] = [];

  for (const [id, targetChild] of target) {
    const previousChild = previous.get(id);

    if (!previousChild) {
      changes.push({
        kind: "added",
        id,
        target: targetChild,
      });
    } else if (!previousChild.eq(targetChild)) {
      changes.push({
        kind: "modified",
        id,
        previous: previousChild,
        target: targetChild,
      });
    }
  }

  for (const [id, previousChild] of previous) {
    if (!target.has(id)) {
      changes.push({
        kind: "removed",
        id,
        previous: previousChild,
      });
    }
  }

  return changes;
}
