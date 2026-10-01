import { buildDoc, createDocEditor, type DocWeaveSnapshot } from "../../src/index.js";

type NodeJSON = {
  type: string;
  attrs?: Record<string, unknown>;
  text?: string;
  content?: NodeJSON[];
};

/** An order with a heading paragraph and two numbered clauses, as generated. */
export function generatedOrder(): DocWeaveSnapshot {
  const controller = createDocEditor();
  controller.render(buildDoc((doc) => {
    doc.paragraph("heading", "IT IS ORDERED THAT:");
    doc.orderedList("clauses", (list) => {
      list.item("possession", (content) => {
        content
          .text("The defendant must give up possession by ")
          .fact("deadline", "1 October 2026", { sourceId: "deadline" })
          .text(".");
      });
      list.item("costs", "The defendant must pay the claimant's costs.");
    });
  }));
  return controller.getSnapshot();
}

/** The snapshot with the reader's document changed by `edit`. */
export function edited(
  snapshot: DocWeaveSnapshot,
  edit: (current: NodeJSON) => void,
): DocWeaveSnapshot {
  const current = structuredClone(snapshot.current) as NodeJSON;
  edit(current);
  return { ...snapshot, current };
}

export const clauses = (current: NodeJSON): NodeJSON[] => current.content![1]!.content!;

export const userClause = (text: string): NodeJSON => ({
  type: "list_item",
  attrs: { id: null },
  content: [{ type: "paragraph", attrs: { id: null }, content: [{ type: "text", text }] }],
});
