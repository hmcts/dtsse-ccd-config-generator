import { baseKeymap } from "prosemirror-commands";
import { dropCursor } from "prosemirror-dropcursor";
import {
  buildInputRules,
  buildKeymap,
  buildMenuItems,
} from "prosemirror-example-setup";
import { gapCursor } from "prosemirror-gapcursor";
import { history } from "prosemirror-history";
import { keymap } from "prosemirror-keymap";
import { menuBar } from "prosemirror-menu";
import {
  type Node as ProseMirrorNode,
  Schema,
} from "prosemirror-model";
import { EditorState, Plugin } from "prosemirror-state";
import {Decoration, DecorationSet, EditorView} from "prosemirror-view";
import { schema } from "prosemirror-schema-basic";
import { addListNodes } from "prosemirror-schema-list";

import "prosemirror-view/style/prosemirror.css";
import "prosemirror-menu/style/menu.css";
import "prosemirror-example-setup/style/style.css";
import "prosemirror-gapcursor/style/gapcursor.css";

const documentDebug = document.querySelector<HTMLElement>("#document-debug");
const htmlDebug = document.querySelector<HTMLElement>("#html-debug");
const nodesDebug = document.querySelector<HTMLElement>("#nodes-debug");
const marksDebug = document.querySelector<HTMLElement>("#marks-debug");
const schemaDebug = document.querySelector<HTMLElement>("#schema-debug");

if (!documentDebug || !htmlDebug || !nodesDebug || !marksDebug || !schemaDebug) {
  throw new Error("The editor page is missing its ProseMirror mount points");
}

const documentDebugElement = documentDebug;
const htmlDebugElement = htmlDebug;

const allowedNodeNames = ["doc", "paragraph", "heading", "text"];
const allowedMarkNames = ["em", "strong"];

const allowedSchema = new Schema({
  nodes: Object.fromEntries(
    allowedNodeNames.map((name) => [name, schema.spec.nodes.get(name)!]),
  ),
  marks: Object.fromEntries(
    allowedMarkNames.map((name) => [name, schema.spec.marks.get(name)!]),
  ),
});

const paragraphSpec = allowedSchema.spec.nodes.get("paragraph")!;
const nodesWithParagraphId = allowedSchema.spec.nodes.update("paragraph", {
  ...paragraphSpec,
  attrs: {
    ...paragraphSpec.attrs,
    id: { default: null },
  },
});

const editorSchema = new Schema({
  nodes: addListNodes(
    nodesWithParagraphId,
    "paragraph block*",
    "block",
  ),
  marks: allowedSchema.spec.marks,
});

nodesDebug.textContent = Object.keys(editorSchema.nodes).join("\n");
marksDebug.textContent = Object.keys(editorSchema.marks).join("\n");
schemaDebug.textContent = JSON.stringify(
  {
    nodes: editorSchema.spec.nodes.toObject(),
    marks: editorSchema.spec.marks.toObject(),
  },
  (_key, value) => typeof value === "function" ? "[Function]" : value,
  2,
);

function formatHtml(html: string): string {
  let depth = 0;

  return html.replace(/></g, ">\n<").split("\n").map((line) => {
    if (line.startsWith("</")) depth--;
    const indentedLine = `${"  ".repeat(depth)}${line}`;
    if (/^<[^/!][^>]*>$/.test(line)) depth++;
    return indentedLine;
  }).join("\n");
}

const doc = editorSchema.topNodeType.createAndFill()!;

let purplePlugin = new Plugin({
  props: {
    decorations(state) {
      const decorations: Decoration[] = [];

      state.doc.descendants((node, pos) => {
        const original = originalClauses.get(node.attrs.id);
        if (
          original != undefined &&
          !node.eq(original)
        ) {
          decorations.push(
            Decoration.node(pos, pos + node.nodeSize, {
              style: "color: red",
            }),
          );
          decorations.push(
            Decoration.widget(pos + 1, () => {
              const button = document.createElement("button");
              button.type = "button";
              button.className = "revert-node-button";
              button.dataset.nodePosition = String(pos);
              button.setAttribute("aria-label", "Revert this paragraph");
              button.title = "Revert this paragraph";
              button.textContent = "↶";
              return button;
            }, { side: -1 }),
          );
        }
      });

      return DecorationSet.create(state.doc, decorations);
    },
    handleClick(view, _pos, event) {
      if (/revert-node-button/.test((event.target as HTMLElement).className)) {
        const nodePosition = Number((event.target as HTMLElement).dataset.nodePosition);
        const node = view.state.doc.nodeAt(nodePosition);
        const original = originalClauses.get(node?.attrs.id);

        if (node != null && original != null)
          view.dispatch(
            view.state.tr
              .replaceWith(nodePosition, nodePosition + node.nodeSize, original)
              .scrollIntoView(),
          );

        view.focus();
        return true;
      }
    },
  },
});

const state = EditorState.create({
  doc: doc,
  plugins: [
    buildInputRules(editorSchema),
    keymap(buildKeymap(editorSchema)),
    keymap(baseKeymap),
    dropCursor(),
    purplePlugin,
    gapCursor(),
    menuBar({
      floating: true,
      content: buildMenuItems(editorSchema).fullMenu,
    }),
    history(),
    new Plugin({
      props: {
        attributes: {
          class: "ProseMirror-example-setup-style",
        },
      },
    }),
  ],
});

export interface OrderEditor {
  setClause(id: string, text: string): void;
  removeClause(id: string): void;
}

const originalClauses = new Map<string, ProseMirrorNode>();

export function createOrderEditor(selector: string): OrderEditor {
  const editor = document.querySelector<HTMLElement>(selector);

  if (!editor) {
    throw new Error(`Editor element not found: ${selector}`);
  }

  const view = new EditorView(editor, {
    state,
    dispatchTransaction(transaction) {
      const nextState = view.state.apply(transaction);
      view.updateState(nextState);
      documentDebugElement.textContent = JSON.stringify(
        nextState.doc.toJSON(),
        null,
        2,
      );
      htmlDebugElement.textContent = formatHtml(view.dom.outerHTML);
    },
  });

  function findClause(
    id: string,
  ): { node: ProseMirrorNode; position: number } | undefined {
    let clause: { node: ProseMirrorNode; position: number } | undefined;

    view.state.doc.forEach((node, position) => {
      if (!clause && node.attrs.id === id) {
        clause = { node, position };
      }
    });

    return clause;
  }


  function setClause(id: string, text: string): void {
    const clauseNode = editorSchema.node(
      "paragraph",
      { id },
      editorSchema.text(text)
    );
    const existingClause = findClause(id);

    originalClauses.set(id, clauseNode);
    if (existingClause?.node.eq(clauseNode)) return;

    const transaction = existingClause
      ? view.state.tr.replaceWith(
          existingClause.position,
          existingClause.position + existingClause.node.nodeSize,
          clauseNode,
        )
      : view.state.doc.textContent
        ? view.state.tr.insert(view.state.doc.content.size, clauseNode)
        : view.state.tr.replaceWith(
            0,
            view.state.doc.content.size,
            clauseNode,
          );

    view.dispatch(transaction);
  }

  function removeClause(id: string): void {
    const existingClause = findClause(id);

    originalClauses.delete(id);
    if (!existingClause) return;

    view.dispatch(
      view.state.tr.delete(
        existingClause.position,
        existingClause.position + existingClause.node.nodeSize,
      ),
    );
  }

  documentDebugElement.textContent = JSON.stringify(
    view.state.doc.toJSON(),
    null,
    2,
  );
  htmlDebugElement.textContent = formatHtml(view.dom.outerHTML);

  return { setClause, removeClause };
}
