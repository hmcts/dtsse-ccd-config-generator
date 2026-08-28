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
  Fragment,
  type Node as ProseMirrorNode,
} from "prosemirror-model";
import {
  EditorState,
  Plugin,
  type Command,
} from "prosemirror-state";
import {EditorView} from "prosemirror-view";

import {
  insertUserParagraphAfterSystem,
  splitUserListItem,
  wrapInBulletList,
  wrapInOrderedList,
} from "./commands.js";
import { editorSchema } from "./schema.js";

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

const menuItems = buildMenuItems(editorSchema);

function useCommand(item: MenuItem | undefined, command: Command): void {
  if (!item) return;

  item.spec.run = command;
  item.spec.enable = (state) => command(state);
}

useCommand(menuItems.wrapBulletList, wrapInBulletList);
useCommand(menuItems.wrapOrderedList, wrapInOrderedList);

const generatedKeymap = buildKeymap(editorSchema, {
  "Enter": false,
  "Shift-Ctrl-8": false,
  "Shift-Ctrl-9": false,
});

function createEditorState(): EditorState {
  return EditorState.create({
    schema: editorSchema,
    plugins: [
    buildInputRules(editorSchema),
    keymap({ Enter: insertUserParagraphAfterSystem }),
    keymap({ Enter: splitUserListItem }),
    keymap({
      "Shift-Ctrl-8": wrapInBulletList,
      "Shift-Ctrl-9": wrapInOrderedList,
    }),
    keymap(generatedKeymap),
    keymap(baseKeymap),
    dropCursor(),
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
}

export interface OrderController {
  render(target: ProseMirrorNode): void;
}

interface TopLevelClause {
  id: string;
  node: ProseMirrorNode;
  position: number;
}

function topLevelClauses(document: ProseMirrorNode): TopLevelClause[] {
  const clauses: TopLevelClause[] = [];

  document.forEach((node, position) => {
    if (typeof node.attrs.id === "string") {
      clauses.push({ id: node.attrs.id, node, position });
    }
  });

  return clauses;
}

function findTopLevelClause(
  document: ProseMirrorNode,
  id: string,
): TopLevelClause | undefined {
  let result: TopLevelClause | undefined;

  document.forEach((node, position) => {
    if (node.attrs.id === id) {
      result = { id, node, position };
    }
  });

  return result;
}

function reconcileNode(
  liveNode: ProseMirrorNode,
  targetNode: ProseMirrorNode,
): ProseMirrorNode {
  if (liveNode.type !== targetNode.type) return targetNode;

  const targetChildren = targetNode.content.content;
  if (
    targetChildren.length === 0 ||
    targetChildren.some((child) => typeof child.attrs.id !== "string")
  ) {
    return targetNode;
  }

  const targetIndexes = new Map(
    targetChildren.map((child, index) => [child.attrs.id as string, index]),
  );
  const reconciledChildren: ProseMirrorNode[] = [];
  let targetIndex = 0;

  liveNode.forEach((liveChild) => {
    const id = liveChild.attrs.id;
    if (typeof id !== "string") {
      reconciledChildren.push(liveChild);
      return;
    }

    const matchingIndex = targetIndexes.get(id);
    if (matchingIndex === undefined || matchingIndex < targetIndex) return;

    while (targetIndex < matchingIndex) {
      reconciledChildren.push(targetChildren[targetIndex]!);
      targetIndex++;
    }

    reconciledChildren.push(
      reconcileNode(liveChild, targetChildren[targetIndex]!),
    );
    targetIndex++;
  });

  while (targetIndex < targetChildren.length) {
    reconciledChildren.push(targetChildren[targetIndex]!);
    targetIndex++;
  }

  return targetNode.copy(Fragment.fromArray(reconciledChildren));
}

function validateTarget(target: ProseMirrorNode): void {
  const ids = new Set<string>();

  target.descendants((node) => {
    const id = node.attrs.id;
    if (typeof id !== "string") return;
    if (ids.has(id)) throw new Error(`Duplicate target clause ID: ${id}`);
    ids.add(id);
  });
}

export function createOrderEditor(
  selector: string
): OrderController {
  const editor = document.querySelector<HTMLElement>(selector);
  if (!editor) {
    throw new Error(`Editor element not found: ${selector}`);
  }

  const view = new EditorView(editor, {
    state: createEditorState(),
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

  documentDebugElement.textContent = JSON.stringify(
    view.state.doc.toJSON(),
    null,
    2,
  );
  htmlDebugElement.textContent = formatHtml(view.dom.outerHTML);

  let hasRendered = false;

  const controller: OrderController = {
    render(target: ProseMirrorNode): void {
      const targetClauses = topLevelClauses(target);
      if (targetClauses.length !== target.childCount) {
        throw new Error("Every top-level target clause must have an ID");
      }

      validateTarget(target);
      const targetIds = new Set(targetClauses.map((clause) => clause.id));

      if (!hasRendered) {
        hasRendered = true;
        view.dispatch(
          view.state.tr
            .replaceWith(0, view.state.doc.content.size, target.content)
            .setMeta("addToHistory", false),
        );
        return;
      }

      let transaction = view.state.tr;

      const removedClauses = topLevelClauses(transaction.doc)
        .filter((clause) => !targetIds.has(clause.id))
        .reverse();
      for (const clause of removedClauses) {
        transaction.delete(
          clause.position,
          clause.position + clause.node.nodeSize,
        );
      }

      let insertionPosition = 0;

      for (const targetClause of targetClauses) {
        const liveClause = findTopLevelClause(
          transaction.doc,
          targetClause.id,
        );
        let renderedNode = targetClause.node;

        if (!liveClause) {
          transaction.insert(insertionPosition, renderedNode);
        } else {
          renderedNode = reconcileNode(
            liveClause.node,
            targetClause.node,
          );
          if (!liveClause.node.eq(renderedNode)) {
            transaction.replaceWith(
              liveClause.position,
              liveClause.position + liveClause.node.nodeSize,
              renderedNode,
            );
          }
        }

        insertionPosition = liveClause?.position ?? insertionPosition;
        insertionPosition += renderedNode.nodeSize;
      }

      if (transaction.docChanged) {
        view.dispatch(transaction.setMeta("addToHistory", false));
      }
    }
  };

  return controller;
}
