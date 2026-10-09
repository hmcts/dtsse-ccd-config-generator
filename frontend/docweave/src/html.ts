import { DOMSerializer, type DOMOutputSpec, type Mark, type Node as ProseMirrorNode } from "prosemirror-model";

import {
  CHANGE_DESCRIPTIONS,
  type ClauseChange,
  clauseChange,
  clauseNodesById,
  isClauseNode,
  parseSnapshot,
} from "./changes.js";
import { type DocWeaveSnapshot } from "./controller.js";
import { listStarts } from "./list-numbering.js";
import { outputSchema, toOutputDocument } from "./output-schema.js";

export interface RenderHtmlOptions {
  /** @deprecated Rendering no longer needs a DOM. Accepted for existing callers. */
  document?: Document;
  /** Marks inserted and modified clauses with the editor's classes and accessible descriptions. */
  changes?: boolean;
}

const serializer = DOMSerializer.fromSchema(outputSchema);

/** Renders the validated document using ProseMirror's output schema, without a browser DOM. */
export function renderHtml(snapshot: DocWeaveSnapshot, options: RenderHtmlOptions = {}): string {
  const output = toOutputDocument(snapshot.current);
  const starts = listStarts(output);
  const topLevelLists = new Map<ProseMirrorNode, number>();
  output.forEach((node, _offset, index) => {
    const start = starts[index];
    if (start !== undefined) topLevelLists.set(node, start);
  });
  const changes = new Map<ProseMirrorNode, ClauseChange>();
  if (options.changes) {
    const { current, generated } = parseSnapshot(snapshot);
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

  const renderNode = (node: ProseMirrorNode, description = ""): string => {
    if (node.isText) return escapeHtml(node.text!);
    const change = changes.get(node);
    const prefix = change
      ? `<span class="docweave-editor__visually-hidden">${CHANGE_DESCRIPTIONS[change.kind]} </span>`
      : description;
    const content = node.type.name === "list_item"
      ? node.children.map((child, index) => renderNode(child, index === 0 ? prefix : "")).join("")
      : prefix + renderChildren(node) + (prefix && !node.childCount ? "<br>" : "");
    const spec = serializer.nodes[node.type.name]!(node);
    const { open, close } = htmlWrapper(spec, change
      ? `docweave-editor__clause docweave-editor__clause--${change.kind}`
      : undefined, node.type.name === "ordered_list"
      ? topLevelLists.has(node)
        ? { start: topLevelLists.get(node) === 1 ? null : topLevelLists.get(node) }
        : { type: "i" }
      : {});
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
      html += renderNode(node);
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
