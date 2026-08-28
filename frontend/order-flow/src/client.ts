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
import { menuBar, type MenuItem } from "prosemirror-menu";
import {
  type Node as ProseMirrorNode,
  Schema,
} from "prosemirror-model";
import {
  EditorState,
  Plugin,
  TextSelection,
  type Command,
  type Transaction,
} from "prosemirror-state";
import {Decoration, DecorationSet, EditorView} from "prosemirror-view";
import { schema } from "prosemirror-schema-basic";
import { addListNodes, wrapInList } from "prosemirror-schema-list";

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

const nodesWithLists = addListNodes(
  nodesWithParagraphId,
  "paragraph block*",
  "block",
);

const orderedListSpec = nodesWithLists.get("ordered_list")!;
const nodesWithListId = nodesWithLists.update("ordered_list", {
  ...orderedListSpec,
  attrs: {
    ...orderedListSpec.attrs,
    id: { default: null },
  },
});

const listItemSpec = nodesWithListId.get("list_item")!;
const nodesWithListItemId = nodesWithListId.update("list_item", {
  ...listItemSpec,
  attrs: {
    ...listItemSpec.attrs,
    id: { default: null },
  },
});

const editorSchema = new Schema({
  nodes: nodesWithListItemId,
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

let debugPlugin = new Plugin({
  props: {
    decorations(state) {

      state.doc.descendants((node, pos) => {
        console.log(node.type.name, node.toString())
      });

      return DecorationSet.create(state.doc, []);
    }
  }
});

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
        if (node.type.name == "text")
          return false;
        if (node.attrs.id == null) {
          console.log(node.type.name)
          decorations.push(
            Decoration.node(pos, pos + node.nodeSize, {
              class: "user-authored-paragraph",
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
          // Don't recursively scan children mark only the outer user created node
          return false;
        }
      });

      return DecorationSet.create(state.doc, decorations);
    },
    handleClick(view, _pos, event) {
      if (/revert-node-button/.test((event.target as HTMLElement).className)) {
        const nodePosition = Number((event.target as HTMLElement).dataset.nodePosition);
        const node = view.state.doc.nodeAt(nodePosition);
        const original = originalClauses.get(node?.attrs.id);

        if (node != null)
          if (original != null) {
            view.dispatch(
              view.state.tr
                .replaceWith(nodePosition, nodePosition + node.nodeSize, original)
                .scrollIntoView(),
            );
          } else {
            view.dispatch(
              view.state.tr
                .deleteRange(nodePosition, nodePosition + node.nodeSize)
                .scrollIntoView(),
            );
          }

        view.focus();
        return true;
      }
    },
  },
});

function insertUserParagraphAfterSystem(
  state: EditorState,
  dispatch?: (transaction: Transaction) => void,
): boolean {
  if (!state.selection.empty) {
    return selectionTouchesSystemParagraph(state);
  }

  const { $from } = state.selection;

  if (
    $from.depth !== 1 ||
    typeof $from.parent.attrs.id !== "string"
  ) {
    return false;
  }

  if (dispatch) {
    const insertPosition = $from.after();
    const paragraph = editorSchema.node("paragraph");
    const transaction = state.tr.insert(insertPosition, paragraph);

    transaction.setSelection(
      TextSelection.create(transaction.doc, insertPosition + 1),
    );
    dispatch(transaction.scrollIntoView());
  }

  return true;
}

function selectionTouchesSystemParagraph(state: EditorState): boolean {
  if (typeof state.selection.$from.parent.attrs.id === "string") {
    return true;
  }

  let touchesSystemParagraph = false;

  state.doc.nodesBetween(
    state.selection.from,
    state.selection.to,
    (node) => {
      if (node.isTextblock && typeof node.attrs.id === "string") {
        touchesSystemParagraph = true;
        return false;
      }
    },
  );

  return touchesSystemParagraph;
}

function outsideSystemParagraph(command: Command): Command {
  return (state, dispatch, view) =>
    !selectionTouchesSystemParagraph(state) &&
    command(state, dispatch, view);
}

const wrapInBulletList = outsideSystemParagraph(
  wrapInList(editorSchema.nodes.bullet_list!),
);
const wrapInOrderedList = outsideSystemParagraph(
  wrapInList(editorSchema.nodes.ordered_list!),
);

const menuItems = buildMenuItems(editorSchema);

function useCommand(item: MenuItem | undefined, command: Command): void {
  if (!item) return;

  item.spec.run = command;
  item.spec.enable = (state) => command(state);
}

useCommand(menuItems.wrapBulletList, wrapInBulletList);
useCommand(menuItems.wrapOrderedList, wrapInOrderedList);

const generatedKeymap = buildKeymap(editorSchema, {
  "Shift-Ctrl-8": false,
  "Shift-Ctrl-9": false,
});

const state = EditorState.create({
  doc: doc,
  plugins: [
    buildInputRules(editorSchema),
    keymap({ Enter: insertUserParagraphAfterSystem }),
    keymap({
      "Shift-Ctrl-8": wrapInBulletList,
      "Shift-Ctrl-9": wrapInOrderedList,
    }),
    keymap(generatedKeymap),
    keymap(baseKeymap),
    dropCursor(),
    purplePlugin,
    // debugPlugin,
    gapCursor(),
    menuBar({
      floating: true,
      content: menuItems.fullMenu,
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

export interface ListBuilder {
  listItem(id: string, text: string): ListBuilder;
  build(): void;
}

export interface OrderEditor {
  setClause(id: string, text: string): void;
  buildList(id: string): ListBuilder;
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

  function setList(id: string, listItems: ProseMirrorNode[]): void {
    const clauseNode = editorSchema.node(
      "ordered_list",
      { id },
      listItems,
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

  function buildList(id: string): ListBuilder {
    const listItems: ProseMirrorNode[] = [];
    const builder: ListBuilder = {
      listItem(itemId: string, text: string): ListBuilder {
        const paragraph = editorSchema.node(
          "paragraph",
          { id: itemId },
          editorSchema.text(text),
        );

        listItems.push(editorSchema.node("list_item", { id: "li-" + itemId}, paragraph));
        return builder;
      },
      build(): void {
        setList(id, listItems);
      }
    };

    return builder;
  }

  return { setClause, removeClause, buildList };
}
