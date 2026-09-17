/**
 * Realm-free DOM checks. An editor may be mounted in another document, whose
 * nodes are not instances of this module's global constructors.
 */
export function isNode(value: unknown): value is Node {
  return typeof value === "object" && value !== null && "nodeType" in value;
}

export function isElement(value: unknown): value is Element {
  return isNode(value) && value.nodeType === 1;
}

export function isFocusable(value: unknown): value is HTMLElement {
  return isElement(value) && "focus" in value;
}
