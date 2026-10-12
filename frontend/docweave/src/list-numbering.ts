import { type Node as ProseMirrorNode } from "prosemirror-model";
import { Plugin } from "prosemirror-state";
import { Decoration, DecorationSet } from "prosemirror-view";

/**
 * The number each of a document's top-level blocks starts at, for the ordered
 * lists among them: the lists count on from one another, so clauses are
 * numbered through the document whatever lies between them. Other blocks get
 * no number.
 */
export function listStarts(doc: ProseMirrorNode): Array<number | undefined> {
  const starts: Array<number | undefined> = [];
  let nextNumber = 1;
  doc.forEach((node) => {
    if (node.type.name !== "ordered_list") {
      starts.push(undefined);
      return;
    }
    starts.push(nextNumber);
    nextNumber += node.childCount;
  });
  return starts;
}

export function createListNumberingPlugin(): Plugin {
  return new Plugin({
    props: {
      decorations(state) {
        const decorations: Decoration[] = [];
        const starts = listStarts(state.doc);

        state.doc.forEach((node, position, index) => {
          const start = starts[index];
          if (start === undefined) return;

          decorations.push(
            Decoration.node(
              position,
              position + node.nodeSize,
              { start: String(start) },
            ),
          );
        });

        return DecorationSet.create(state.doc, decorations);
      },
    },
  });
}
