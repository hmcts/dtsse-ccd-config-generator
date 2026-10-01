import { DOMSerializer, type Node as ProseMirrorNode } from "prosemirror-model";

import {
  clauseChange,
  clauseNodesById,
  ownContent,
  parseSnapshot,
} from "./changes.js";
import { type DocWeaveSnapshot } from "./controller.js";
import { outputSchema, toOutputDocument } from "./output-schema.js";

export interface RenderHtmlOptions {
  /** The DOM used to build the markup; defaults to the global document. */
  document?: Document;
  /**
   * Shows how the reader changed the generated document, clause by clause as
   * the editor marks it: a clause they wrote is marked inserted, and a
   * generated clause they reworded shows the generated wording deleted and
   * theirs inserted, whole. Each changed clause says how in
   * `data-docweave-change`.
   */
  changes?: boolean;
}

/**
 * Renders the reader's document from a snapshot as plain HTML: the wording as
 * they left it, in the output schema, ready to become the final document.
 */
export function renderHtml(
  snapshot: DocWeaveSnapshot,
  options: RenderHtmlOptions = {},
): string {
  const document = options.document ?? globalThis.document;
  if (!document) {
    throw new Error("renderHtml needs a DOM document; pass one in options");
  }
  const container = document.createElement("div");
  container.append(
    DOMSerializer.fromSchema(outputSchema).serializeFragment(
      toOutputDocument(snapshot.current).content,
      { document },
    ),
  );
  if (options.changes) {
    markChanges(snapshot, container);
  }
  return container.innerHTML;
}

/**
 * Marks the reader's changes in the rendered document. The output keeps the
 * editor document's blocks one for one, so each clause is found at the same
 * place in both.
 */
function markChanges(snapshot: DocWeaveSnapshot, container: HTMLElement): void {
  const { current, generated } = parseSnapshot(snapshot);
  markChildren(current, container, clauseNodesById(generated));
}

/** Marks the clauses among a document's blocks or a list's items, and those of the lists among them. */
function markChildren(
  parent: ProseMirrorNode,
  element: Element,
  generatedClauses: Map<string, ProseMirrorNode>,
): void {
  parent.forEach((child, _offset, index) => {
    const childElement = element.children[index]!;
    if (child.type.name === "ordered_list") {
      markChildren(child, childElement, generatedClauses);
    } else {
      markClause(child, childElement, generatedClauses);
    }
  });
}

function markClause(
  clause: ProseMirrorNode,
  element: Element,
  generatedClauses: Map<string, ProseMirrorNode>,
): void {
  const change = clauseChange(clause, generatedClauses);
  if (change) {
    element.setAttribute("data-docweave-change", change.kind);
    const own = ownContent(clause);
    // A list item's own paragraphs come before any nested list; any other clause is its own block.
    const ownElements = clause.type.name === "list_item" ? [...element.children].slice(0, own.length) : [element];
    const generatedOwn = change.kind === "modified" ? ownContent(change.generated) : [];
    // A block that reads as a generated one does is left as it is, so formatting alone shows no change.
    const reworded = ownElements.filter((_ownElement, index) => !generatedOwn.some(readsAs(own[index]!)));
    reworded.forEach((ownElement) => wrapContent(ownElement, "ins"));
    const replaced = generatedOwn.filter((block) => !own.some(readsAs(block)));
    (reworded[0] ?? ownElements[0]!).prepend(...replaced.map((block) => {
      const deleted = element.ownerDocument.createElement("del");
      deleted.textContent = block.textContent;
      return deleted;
    }));
  }
  if (clause.lastChild?.type.name === "ordered_list") {
    markChildren(clause.lastChild, element.lastElementChild!, generatedClauses);
  }
}

function readsAs(block: ProseMirrorNode): (other: ProseMirrorNode) => boolean {
  return (other) => other.textContent === block.textContent;
}

function wrapContent(element: Element, tag: "ins"): void {
  const wrapper = element.ownerDocument.createElement(tag);
  wrapper.append(...element.childNodes);
  element.append(wrapper);
}
