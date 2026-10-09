import { DOMSerializer, type Node as ProseMirrorNode } from "prosemirror-model";

import {
  CHANGE_DESCRIPTIONS,
  clauseChange,
  clauseNodesById,
  parseSnapshot,
} from "./changes.js";
import { type DocWeaveSnapshot } from "./controller.js";
import { listStarts } from "./list-numbering.js";
import { outputSchema, toOutputDocument } from "./output-schema.js";

export interface RenderHtmlOptions {
  /** The DOM used to build the markup; defaults to the global document. */
  document?: Document;
  /**
   * Marks the clauses the reader wrote or changed as the editor marks them
   * for the reader, with its classes and the words it says before each, so
   * the editor's stylesheet shows someone else what the reader saw.
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
  const output = toOutputDocument(snapshot.current);
  container.append(
    DOMSerializer.fromSchema(outputSchema).serializeFragment(output.content, { document }),
  );
  numberAsTheEditorShows(output, container);
  if (options.changes) {
    markChanges(snapshot, container);
  }
  return container.innerHTML;
}

/**
 * Numbers the lists as the editor shows them, which its document leaves to the
 * view: the top-level lists count on from one another, and a list within a
 * clause is numbered i, ii, iii.
 */
function numberAsTheEditorShows(output: ProseMirrorNode, container: HTMLElement): void {
  listStarts(output).forEach((start, index) => {
    if (start === undefined) return;
    const list = container.children[index]!;
    if (start === 1) {
      list.removeAttribute("start");
    } else {
      list.setAttribute("start", String(start));
    }
  });
  for (const nested of container.querySelectorAll("ol ol")) {
    nested.setAttribute("type", "i");
  }
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
    element.classList.add("docweave-editor__clause", `docweave-editor__clause--${change.kind}`);
    const description = element.ownerDocument.createElement("span");
    description.className = "docweave-editor__visually-hidden";
    description.textContent = `${CHANGE_DESCRIPTIONS[change.kind]} `;
    // A list item's wording starts in its first paragraph; any other clause is its own block.
    const wording = clause.type.name === "list_item" ? element.firstElementChild! : element;
    // An empty block has no height to show its mark on; the editor gives it a line break too.
    if (!wording.hasChildNodes()) wording.append(element.ownerDocument.createElement("br"));
    wording.prepend(description);
  }
  if (clause.lastChild?.type.name === "ordered_list") {
    markChildren(clause.lastChild, element.lastElementChild!, generatedClauses);
  }
}
