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
  const own = ownContent(clause);
  // A list item's own paragraphs come before any nested list; any other clause is its own block.
  const ownElements = clause.type.name === "list_item" ? [...element.children].slice(0, own.length) : [element];
  const change = clauseChange(clause, generatedClauses);
  if (change?.kind === "inserted") {
    element.setAttribute("data-docweave-change", "inserted");
    ownElements.forEach((ownElement) => wrapContent(ownElement, "ins"));
  } else if (change?.kind === "modified") {
    element.setAttribute("data-docweave-change", "modified");
    markRewording(ownContent(change.generated), own, ownElements);
  }
  if (clause.lastChild?.type.name === "ordered_list") {
    markChildren(clause.lastChild, element.lastElementChild!, generatedClauses);
  }
}

function wrapContent(element: Element, tag: "ins" | "del"): void {
  const wrapper = element.ownerDocument.createElement(tag);
  wrapper.append(...element.childNodes);
  element.append(wrapper);
}

/**
 * Shows a reworded clause's blocks as the generated wording changed into the
 * reader's. Blocks left as generated are matched first, so a block the reader
 * added before a generated one is inserted rather than read as rewording it;
 * between those, generated and reader's blocks are compared in turn, word by
 * word. Docweave generates a clause with one block, which the reader cannot
 * remove, so no generated block is left over.
 */
function markRewording(
  generatedBlocks: ProseMirrorNode[],
  blocks: ProseMirrorNode[],
  elements: Element[],
): void {
  const document = elements[0]!.ownerDocument;
  let position = 0;
  for (const edit of diff(generatedBlocks, blocks, (a, b) => a.eq(b))) {
    if ("same" in edit) {
      position += edit.same.length;
      continue;
    }
    edit.ins.forEach((block, index) => {
      const element = elements[position + index]!;
      const generatedBlock = edit.del[index];
      if (!generatedBlock) {
        wrapContent(element, "ins");
      } else if (generatedBlock.textContent !== block.textContent) {
        // Where formatting alone changed, the wording reads as it did and keeps its formatting.
        element.replaceChildren(...wordDiff(generatedBlock.textContent, block.textContent, document));
      }
    });
    position += edit.ins.length;
  }
}

/**
 * Beyond this many comparisons the diff would take too long and too much memory, so the two
 * sides are shown as wholly replaced instead.
 */
const MAX_DIFF_COMPARISONS = 1_000_000;

type Edit<T> = { same: T[] } | { del: T[]; ins: T[] };

/**
 * One list changed into another: runs of items they share, and between them
 * the items taken out and those put in. Lists too long to compare are wholly
 * replaced.
 */
function diff<T>(from: readonly T[], to: readonly T[], same: (a: T, b: T) => boolean): Edit<T>[] {
  const lengths = from.length * to.length > MAX_DIFF_COMPARISONS
    ? undefined
    : longestCommonSubsequenceLengths(from, to, same);
  const edits: Edit<T>[] = [];
  const add = (edit: Edit<T>): void => {
    const last = edits[edits.length - 1];
    if (last && "same" in last && "same" in edit) {
      last.same.push(...edit.same);
    } else if (last && "del" in last && "del" in edit) {
      last.del.push(...edit.del);
      last.ins.push(...edit.ins);
    } else {
      edits.push(edit);
    }
  };
  let i = 0;
  let j = 0;
  while (lengths && i < from.length && j < to.length) {
    if (same(from[i]!, to[j]!)) {
      add({ same: [from[i++]!] });
      j++;
    } else if (lengths[i + 1]![j]! >= lengths[i]![j + 1]!) {
      // Taken out before put in, as a word processor shows a replacement.
      add({ del: [from[i++]!], ins: [] });
    } else {
      add({ del: [], ins: [to[j++]!] });
    }
  }
  if (i < from.length || j < to.length) add({ del: from.slice(i), ins: to.slice(j) });
  return edits;
}

/** For each pair of positions, how long a run of items the rest of the two lists have in common. */
function longestCommonSubsequenceLengths<T>(
  from: readonly T[],
  to: readonly T[],
  same: (a: T, b: T) => boolean,
): number[][] {
  const lengths = Array.from({ length: from.length + 1 }, () => new Array<number>(to.length + 1).fill(0));
  for (let i = from.length - 1; i >= 0; i--) {
    for (let j = to.length - 1; j >= 0; j--) {
      lengths[i]![j] = same(from[i]!, to[j]!)
        ? lengths[i + 1]![j + 1]! + 1
        : Math.max(lengths[i + 1]![j]!, lengths[i]![j + 1]!);
    }
  }
  return lengths;
}

/**
 * The generated wording changed into the reader's, word by word: the words
 * they share as text, and between them the generated words deleted, then the
 * reader's inserted. A lone space between two changes joins them into one,
 * so a replaced phrase reads as one.
 */
function wordDiff(before: string, after: string, document: Document): Node[] {
  const edits = diff(tokens(before), tokens(after), (a, b) => a === b);
  const joined: Edit<string>[] = [];
  for (const edit of edits) {
    const previous = joined[joined.length - 1];
    const beforePrevious = joined[joined.length - 2];
    if ("del" in edit && previous && "same" in previous && previous.same.join("") === " " &&
      beforePrevious && "del" in beforePrevious) {
      joined.pop();
      beforePrevious.del.push(" ", ...edit.del);
      beforePrevious.ins.push(" ", ...edit.ins);
    } else {
      joined.push(edit);
    }
  }
  return joined.flatMap((edit): Node[] => {
    if ("same" in edit) return [document.createTextNode(edit.same.join(""))];
    return (["del", "ins"] as const)
      .filter((tag) => edit[tag].length)
      .map((tag) => {
        const element = document.createElement(tag);
        element.textContent = edit[tag].join("");
        return element;
      });
  });
}

/**
 * Words and the spaces between them. HTML shows a run of spaces as one, so
 * spacing is compared as it reads, such as where an emptied fact left two.
 */
function tokens(text: string): string[] {
  return text.replace(/\s+/g, " ").split(/( )/).filter((token) => token !== "");
}
