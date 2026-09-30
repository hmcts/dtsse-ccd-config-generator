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
   * Shows how the reader changed the generated document, as tracked changes:
   * a clause they wrote is marked inserted, and a generated clause they
   * reworded shows the generated wording deleted and theirs inserted, word by
   * word. Each changed clause says how in `data-docweave-change`. A reworded
   * clause's formatting is not kept, since the wording is compared as text.
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
  const generatedClauses = clauseNodesById(generated);
  const document = container.ownerDocument;

  const visitList = (list: ProseMirrorNode, element: Element): void => {
    list.forEach((item, _offset, index) => {
      visitClause(item, element.children[index]!, [...element.children[index]!.children]);
    });
  };

  const visitClause = (
    clause: ProseMirrorNode,
    element: Element,
    children: Element[],
  ): void => {
    // A list item's own paragraphs are its children; a top-level clause is its own.
    const ownElements = clause.type.name === "list_item"
      ? children.filter((child) => child.tagName !== "OL")
      : [element];
    const change = clauseChange(clause, generatedClauses);
    if (change === "inserted") {
      element.setAttribute("data-docweave-change", "inserted");
      ownElements.forEach((own) => wrapContent(own, "ins"));
    } else if (change === "modified") {
      element.setAttribute("data-docweave-change", "modified");
      markRewording(
        ownContent(generatedClauses.get(clause.attrs.id as string)!),
        ownContent(clause),
        ownElements,
        document,
      );
    }
    if (clause.type.name === "list_item") {
      clause.forEach((child, _offset, index) => {
        if (child.type.name === "ordered_list") visitList(child, children[index]!);
      });
    }
  };

  current.forEach((node, _offset, index) => {
    const element = container.children[index]!;
    if (node.type.name === "ordered_list") {
      visitList(node, element);
    } else {
      visitClause(node, element, [...element.children]);
    }
  });
}

function wrapContent(element: Element, tag: "ins" | "del"): void {
  const wrapper = element.ownerDocument.createElement(tag);
  wrapper.append(...element.childNodes);
  element.append(wrapper);
}

/**
 * Shows a reworded clause's paragraphs as the generated wording changed into
 * the reader's: paragraphs they added are inserted, and generated paragraphs
 * they took out follow their last paragraph, deleted.
 */
function markRewording(
  generatedParagraphs: ProseMirrorNode[],
  paragraphs: ProseMirrorNode[],
  elements: Element[],
  document: Document,
): void {
  paragraphs.forEach((paragraph, index) => {
    const element = elements[index]!;
    const generatedParagraph = generatedParagraphs[index];
    if (!generatedParagraph) {
      wrapContent(element, "ins");
    } else if (!paragraph.eq(generatedParagraph)) {
      element.replaceChildren(
        ...wordDiff(generatedParagraph.textContent, paragraph.textContent, document),
      );
    }
  });
  let last = elements[elements.length - 1]!;
  for (const removed of generatedParagraphs.slice(paragraphs.length)) {
    const element = document.createElement("p");
    const deletion = document.createElement("del");
    deletion.textContent = removed.textContent;
    element.append(deletion);
    last.after(element);
    last = element;
  }
}

type Segment = { kind: "same" | "del" | "ins"; text: string };

/** The generated wording changed into the reader's, word by word, as text, deletions and insertions. */
function wordDiff(before: string, after: string, document: Document): Node[] {
  const from = tokens(before);
  const to = tokens(after);
  // Longest common subsequence of words, then read the edits off it.
  const lengths = Array.from({ length: from.length + 1 }, () => new Array<number>(to.length + 1).fill(0));
  for (let i = from.length - 1; i >= 0; i--) {
    for (let j = to.length - 1; j >= 0; j--) {
      lengths[i]![j] = from[i] === to[j]
        ? lengths[i + 1]![j + 1]! + 1
        : Math.max(lengths[i + 1]![j]!, lengths[i]![j + 1]!);
    }
  }
  const segments: Segment[] = [];
  const push = (kind: Segment["kind"], text: string): void => {
    const previous = segments[segments.length - 1];
    if (previous?.kind === kind) {
      previous.text += text;
    } else {
      segments.push({ kind, text });
    }
  };
  let i = 0;
  let j = 0;
  while (i < from.length || j < to.length) {
    if (i < from.length && j < to.length && from[i] === to[j]) {
      push("same", from[i]!);
      i++;
      j++;
    } else if (i < from.length && (j === to.length || lengths[i + 1]![j]! >= lengths[i]![j + 1]!)) {
      // Deleted wording comes before the wording that replaces it, as in a word processor.
      push("del", from[i]!);
      i++;
    } else {
      push("ins", to[j]!);
      j++;
    }
  }
  return segments.map(({ kind, text }) => {
    if (kind === "same") return document.createTextNode(text);
    const element = document.createElement(kind);
    element.textContent = text;
    return element;
  });
}

/** Words and the spaces between them, so spacing is compared like wording. */
function tokens(text: string): string[] {
  return text.split(/(\s+)/).filter((token) => token !== "");
}
