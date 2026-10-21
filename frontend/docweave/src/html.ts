import { DOMSerializer, type DOMOutputSpec, type Mark, type Node as ProseMirrorNode } from "prosemirror-model";

import {
  CHANGE_DESCRIPTIONS,
  type ClauseChange,
  clauseChange,
  clauseNodesById,
  isClauseNode,
} from "./changes.js";
import { type DocWeaveSnapshot, readSnapshot } from "./snapshot.js";
import { listStarts } from "./list-numbering.js";
import { outputSchema, toOutputDocument } from "./output-schema.js";
import { isList } from "./schema.js";

export interface RenderHtmlOptions {
  /**
   * Marks inserted and modified clauses as the editor does, with the
   * `docweave-clause--inserted` and `--modified` classes and words for a
   * screen reader, which docweave.css styles wherever the HTML is shown.
   */
  changes?: boolean;
}

const serializer = DOMSerializer.fromSchema(outputSchema);

/**
 * Renders the reader's document as HTML using ProseMirror's output schema,
 * without a browser DOM. Throws an InvalidSnapshotError for a snapshot that is
 * not valid.
 */
export function renderHtml(snapshot: DocWeaveSnapshot, options: RenderHtmlOptions = {}): string {
  const { snapshot: valid, current, generated } = readSnapshot(snapshot);
  const output = toOutputDocument(valid.current);
  const starts = listStarts(output);
  const topLevelLists = new Map<ProseMirrorNode, number>();
  output.forEach((node, _offset, index) => {
    const start = starts[index];
    if (start !== undefined) topLevelLists.set(node, start);
  });
  const changes = new Map<ProseMirrorNode, ClauseChange>();
  if (options.changes) {
    const generatedClauses = clauseNodesById(generated);
    const collect = (node: ProseMirrorNode, original: ProseMirrorNode, parent: ProseMirrorNode | null) => {
      if (isClauseNode(original, parent, current)) {
        const change = clauseChange(original, generatedClauses);
        if (change) changes.set(node, change);
      }
      // Facts may disappear from inline content, but the block structure stays one for one.
      if (!node.inlineContent) {
        node.forEach((child, _offset, index) => collect(child, original.child(index), original));
      }
    };
    collect(output, current, null);
  }

  // A list's attributes, given the list of the item it is in, which a list item
  // passes on: subclauses are numbered (i), (ii), but a numbered list under a
  // bullet keeps the numbers it has.
  const listAttributes = (node: ProseMirrorNode, outerList?: ProseMirrorNode) => {
    if (node.type.name !== "ordered_list") return {};
    if (topLevelLists.has(node)) {
      return { start: topLevelLists.get(node) === 1 ? null : topLevelLists.get(node) };
    }
    return outerList?.type.name === "bullet_list" ? {} : { type: "i" };
  };

  const renderNode = (node: ProseMirrorNode, outerList?: ProseMirrorNode, description = ""): string => {
    if (node.isText) return escapeHtml(node.text!);
    if (node.type.name === "hard_break") return "<br>";
    const change = changes.get(node);
    const prefix = change
      ? `<span class="docweave-visually-hidden">${CHANGE_DESCRIPTIONS[change.kind]} </span>`
      : description;
    const content = node.type.name === "list_item"
      ? node.children.map((child, index) => renderNode(child, outerList, index === 0 ? prefix : "")).join("")
      : prefix + renderChildren(node) + (prefix && !node.childCount ? "<br>" : "");
    const spec = serializer.nodes[node.type.name]!(node);
    const { open, close } = htmlWrapper(spec, change
      ? `docweave-clause docweave-clause--${change.kind}`
      : undefined, listAttributes(node, outerList));
    return open + content + close;
  };

  const renderChildren = (parent: ProseMirrorNode): string => {
    let html = "";
    const active: { mark: Mark; close: string }[] = [];
    parent.forEach((node) => {
      let keep = 0;
      while (keep < active.length && keep < node.marks.length &&
        node.marks[keep]!.eq(active[keep]!.mark) && node.marks[keep]!.type.spec.spanning !== false) keep++;
      while (active.length > keep) html += active.pop()!.close;
      for (const mark of node.marks.slice(keep)) {
        const { open, close } = htmlWrapper(serializer.marks[mark.type.name]!(mark, node.isInline));
        html += open;
        active.push({ mark, close });
      }
      html += renderNode(node, isList(parent) ? parent : undefined);
    });
    while (active.length) html += active.pop()!.close;
    return html;
  };

  return renderChildren(output);
}

/**
 * Docweave's schema uses simple element specs with a single content hole.
 * Fail explicitly if a future schema introduces a shape this serializer cannot handle.
 * Tag and attribute names come exclusively from the schema, never the snapshot.
 */
function htmlWrapper(spec: DOMOutputSpec, className?: string, overrides: Record<string, unknown> = {}): { open: string; close: string } {
  if (!Array.isArray(spec) || typeof spec[0] !== "string" || !/^[a-z][a-z0-9]*$/.test(spec[0])) {
    throw new RangeError("Unsupported HTML output specification");
  }
  const tag = spec[0];
  const hasAttrs = spec[1] !== null && typeof spec[1] === "object";
  const attrs: Record<string, unknown> = hasAttrs ? { ...spec[1] } : {};
  const children = spec.slice(hasAttrs ? 2 : 1);
  if (children.length !== 1 || children[0] !== 0) {
    throw new RangeError("HTML output specification must have one content hole");
  }
  Object.assign(attrs, overrides);
  if (className) attrs.class = className;
  const attributes = Object.entries(attrs)
    .filter(([, value]) => value != null)
    .map(([name, value]) => {
      if (!/^[a-z][a-z0-9-]*$/.test(name)) throw new RangeError("Unsupported HTML attribute");
      return ` ${name}="${escapeHtml(String(value), true)}"`;
    }).join("");
  return { open: `<${tag}${attributes}>`, close: `</${tag}>` };
}

/** Match HTML DOM serialization, including non-breaking spaces and quoted attributes. */
function escapeHtml(text: string, attribute = false): string {
  return text.replace(attribute ? /[&"\u00a0]/g : /[&<>\u00a0]/g, (character) => {
    switch (character) {
      case "&": return "&amp;";
      case "<": return "&lt;";
      case ">": return "&gt;";
      case '"': return "&quot;";
      default: return "&nbsp;";
    }
  });
}

/**
 * The reader's document as plain text: one line per paragraph or heading,
 * wherever it sits in a list, with facts as their values and no numbering. A
 * bullet's first line starts with one.
 */
export function renderText(snapshot: DocWeaveSnapshot): string {
  const output = toOutputDocument(readSnapshot(snapshot).snapshot.current);
  const lines: string[] = [];
  const visit = (node: ProseMirrorNode, bullet: boolean): void => {
    node.forEach((child, _offset, index) => {
      if (child.isTextblock) {
        lines.push((bullet && index === 0 ? "\u2022 " : "") + child.textContent);
      } else {
        visit(child, node.type.name === "bullet_list");
      }
    });
  };
  visit(output, false);
  return lines.join("\n");
}
