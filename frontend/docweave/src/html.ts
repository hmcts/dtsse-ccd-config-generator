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
  current.forEach((node, _offset, index) => {
    const element = container.children[index]!;
    if (node.type.name === "ordered_list") {
      markList(node, element, generatedClauses);
    } else {
      markClause(node, element, generatedClauses);
    }
  });
}

function markList(
  list: ProseMirrorNode,
  element: Element,
  generatedClauses: Map<string, ProseMirrorNode>,
): void {
  list.forEach((item, _offset, index) => {
    markClause(item, element.children[index]!, generatedClauses);
  });
}

function markClause(
  clause: ProseMirrorNode,
  element: Element,
  generatedClauses: Map<string, ProseMirrorNode>,
): void {
  // A list item's own paragraphs are its children, beside any nested list; any
  // other clause is a single block.
  const isItem = clause.type.name === "list_item";
  const children = [...element.children];
  const ownElements = isItem
    ? children.filter((child) => child.tagName !== "OL")
    : [element];
  const change = clauseChange(clause, generatedClauses);
  if (change === "inserted") {
    element.setAttribute("data-docweave-change", "inserted");
    ownElements.forEach((own) => wrapContent(own, "ins"));
  } else if (change === "modified") {
    element.setAttribute("data-docweave-change", "modified");
    const generatedClause = generatedClauses.get(clause.attrs.id as string)!;
    markRewording(
      isItem ? ownContent(generatedClause) : [generatedClause],
      isItem ? ownContent(clause) : [clause],
      ownElements,
    );
  }
  if (isItem) {
    clause.forEach((child, _offset, index) => {
      if (child.type.name === "ordered_list") {
        markList(child, children[index]!, generatedClauses);
      }
    });
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
 * word, and any left over are inserted or, for generated ones, deleted.
 */
function markRewording(
  generatedBlocks: ProseMirrorNode[],
  blocks: ProseMirrorNode[],
  elements: Element[],
): void {
  const document = elements[0]!.ownerDocument;
  const pairs = longestCommonSubsequence(generatedBlocks, blocks, (a, b) => a.eq(b));
  let from = 0;
  let to = 0;
  const end: [number, number] = [generatedBlocks.length, blocks.length];
  for (const [matchedFrom, matchedTo] of [...pairs, end]) {
    while (from < matchedFrom || to < matchedTo) {
      if (from < matchedFrom && to < matchedTo) {
        // Formatting alone changed: the wording reads as it did, formatting kept.
        if (generatedBlocks[from]!.textContent !== blocks[to]!.textContent) {
          elements[to]!.replaceChildren(
            ...wordDiff(generatedBlocks[from]!.textContent, blocks[to]!.textContent, document),
          );
        }
        from++;
        to++;
      } else if (to < matchedTo) {
        wrapContent(elements[to]!, "ins");
        to++;
      } else {
        const removed = document.createElement("p");
        removed.append(document.createElement("del"));
        removed.firstChild!.textContent = generatedBlocks[from]!.textContent;
        if (to < elements.length) {
          elements[to]!.before(removed);
        } else {
          elements[elements.length - 1]!.after(removed);
        }
        from++;
      }
    }
    from = matchedFrom + 1;
    to = matchedTo + 1;
  }
}

/**
 * Beyond this many comparisons the diff would take too long and too much memory, so the two
 * sides are shown as wholly replaced instead.
 */
const MAX_DIFF_COMPARISONS = 1_000_000;

/**
 * The index pairs of the longest run of items the two lists have in common, in order, or none
 * when the lists are too long to compare.
 */
function longestCommonSubsequence<T>(
  from: readonly T[],
  to: readonly T[],
  same: (a: T, b: T) => boolean,
): Array<[number, number]> {
  if (from.length * to.length > MAX_DIFF_COMPARISONS) return [];
  const lengths = Array.from({ length: from.length + 1 }, () => new Array<number>(to.length + 1).fill(0));
  for (let i = from.length - 1; i >= 0; i--) {
    for (let j = to.length - 1; j >= 0; j--) {
      lengths[i]![j] = same(from[i]!, to[j]!)
        ? lengths[i + 1]![j + 1]! + 1
        : Math.max(lengths[i + 1]![j]!, lengths[i]![j + 1]!);
    }
  }
  const pairs: Array<[number, number]> = [];
  let i = 0;
  let j = 0;
  while (i < from.length && j < to.length) {
    if (same(from[i]!, to[j]!)) {
      pairs.push([i++, j++]);
    } else if (lengths[i + 1]![j]! >= lengths[i]![j + 1]!) {
      i++;
    } else {
      j++;
    }
  }
  return pairs;
}

type Chunk = { same: string } | { del: string; ins: string };

/**
 * The generated wording changed into the reader's, word by word: the words
 * they share as text, and between them the generated words deleted, then the
 * reader's inserted, as a word processor shows a replacement. A lone space
 * between two changes joins them into one, so a replaced phrase reads as one.
 */
function wordDiff(before: string, after: string, document: Document): Node[] {
  const from = tokens(before);
  const to = tokens(after);
  const chunks: Chunk[] = [];
  let i = 0;
  let j = 0;
  const end: [number, number] = [from.length, to.length];
  for (const [matchedFrom, matchedTo] of [...longestCommonSubsequence(from, to, (a, b) => a === b), end]) {
    const change = { del: from.slice(i, matchedFrom).join(""), ins: to.slice(j, matchedTo).join("") };
    if (change.del || change.ins) chunks.push(change);
    if (matchedFrom < from.length) chunks.push({ same: from[matchedFrom]! });
    i = matchedFrom + 1;
    j = matchedTo + 1;
  }
  const joined: Chunk[] = [];
  for (const chunk of chunks) {
    const previous = joined[joined.length - 1];
    const beforePrevious = joined[joined.length - 2];
    if ("del" in chunk && previous && "same" in previous && /^\s+$/.test(previous.same) &&
      beforePrevious && "del" in beforePrevious) {
      joined.pop();
      beforePrevious.del += previous.same + chunk.del;
      beforePrevious.ins += previous.same + chunk.ins;
    } else if ("same" in chunk && previous && "same" in previous) {
      previous.same += chunk.same;
    } else {
      joined.push(chunk);
    }
  }
  return joined.flatMap((chunk): Node[] => {
    if ("same" in chunk) return [document.createTextNode(chunk.same)];
    return (["del", "ins"] as const)
      .filter((tag) => chunk[tag])
      .map((tag) => {
        const element = document.createElement(tag);
        element.textContent = chunk[tag];
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
