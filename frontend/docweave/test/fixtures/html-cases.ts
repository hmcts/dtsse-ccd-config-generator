import { type DocWeaveSnapshot } from "../../src/index.js";
import { generatedOrder } from "./order.js";

type NodeJSON = {
  type: string;
  attrs?: Record<string, unknown>;
  text?: string;
  marks?: { type: string }[];
  content?: NodeJSON[];
};

export interface HtmlCase { name: string; snapshot: DocWeaveSnapshot }

const text = (value: string, marks: string[] = []): NodeJSON => ({
  type: "text", text: value, marks: marks.map(type => ({ type })),
});
const paragraph = (id: string | null, ...content: NodeJSON[]): NodeJSON => ({
  type: "paragraph", attrs: { id }, content,
});
const snapshot = (content: NodeJSON[], generated = content): DocWeaveSnapshot => ({
  schema: "docweave-document", version: 1,
  current: { type: "doc", content }, generated: { type: "doc", content: generated },
});

/** Stable inputs for exact comparisons between the DOM and DOM-free renderers. */
export function htmlCases(): HtmlCase[] {
  const cases: HtmlCase[] = [{ name: "generated-order", snapshot: generatedOrder() }];
  cases.push({ name: "escaping", snapshot: snapshot([
    paragraph("escaping", text('<script>alert("x")</script> & < > " \' \u00a0\n\t £ café 漢字 😀')),
    paragraph("fact-paragraph", { type: "generated_text", attrs: { id: "fact", text: '<img src=x onerror="bad"> & \u00a0' } }),
    paragraph("empty-fact", text("Before"), { type: "generated_text", attrs: { id: "empty", text: "" } }, text("after")),
  ]) });
  const formats = [[], ["em"], ["strong"], ["em", "strong"]];
  cases.push({ name: "mark-transitions", snapshot: snapshot(formats.flatMap((left, i) =>
    formats.map((right, j) => paragraph(`marks-${i}-${j}`,
      text("left", left), { type: "generated_text", attrs: { id: `fact-${i}-${j}`, text: " fact " }, marks: left.map(type => ({ type })) },
      text("right", right), text(" end"),
    )),
  )) });
  cases.push({ name: "headings", snapshot: snapshot(Array.from({ length: 6 }, (_, index) => ({
    type: "heading", attrs: { level: index + 1 }, content: [text(`Heading ${index + 1}`, ["strong"])],
  }))) });
  cases.push({ name: "editor-list-starts", snapshot: snapshot([1.5, 1e20].map(order => ({
    type: "ordered_list", attrs: { id: `list-${order}`, order }, content: [
      { type: "list_item", attrs: { id: `item-${order}` }, content: [
        paragraph(null, text("Pasted or typed numbering")),
        { type: "ordered_list", attrs: { id: `nested-${order}`, order }, content: [
          { type: "list_item", attrs: { id: `nested-item-${order}` }, content: [paragraph(null, text("Nested numbering keeps its start"))] },
        ] },
      ] },
    ],
  }))) });
  cases.push({ name: "bullets-and-wording-after-subclauses", snapshot: snapshot([
    { type: "ordered_list", attrs: { id: "clauses", order: 1 }, content: [
      { type: "list_item", attrs: { id: "pay" }, content: [
        paragraph(null, text("The defendant must pay:")),
        { type: "bullet_list", attrs: { id: null }, content: [
          { type: "list_item", attrs: { id: null }, content: [
            paragraph(null, text("arrears of:")),
            { type: "ordered_list", attrs: { id: null, order: 3 }, content: [
              { type: "list_item", attrs: { id: null }, content: [paragraph(null, text("rent"))] },
            ] },
          ] },
        ] },
        paragraph(null, text("and keep to the tenancy.")),
        { type: "ordered_list", attrs: { id: null, order: 1 }, content: [
          { type: "list_item", attrs: { id: null }, content: [paragraph(null, text("Subclause"))] },
        ] },
      ] },
    ] },
    { type: "bullet_list", attrs: { id: null }, content: [
      { type: "list_item", attrs: { id: null }, content: [paragraph(null, text("A bullet", ["strong"]))] },
    ] },
    { type: "ordered_list", attrs: { id: null, order: 1 }, content: [
      { type: "list_item", attrs: { id: null }, content: [paragraph(null, text("Second clause"))] },
    ] },
  ], [
    { type: "ordered_list", attrs: { id: "clauses", order: 1 }, content: [
      { type: "list_item", attrs: { id: "pay" }, content: [paragraph(null, text("The defendant must pay:"))] },
    ] },
  ]) });
  cases.push({ name: "blank-document", snapshot: snapshot([paragraph("blank")]) });
  cases.push({ name: "empty-clauses", snapshot: snapshot([
    paragraph("changed"), paragraph(null),
    { type: "ordered_list", attrs: { id: null, order: 5 }, content: [
      { type: "list_item", attrs: { id: null }, content: [paragraph(null), paragraph(null, text("Second paragraph"))] },
    ] },
  ], [paragraph("changed", text("Removed wording"))]) });

  for (let seed = 1; seed <= 12; seed++) {
    let state = seed;
    const random = (limit: number): number => {
      state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
      return (state >>> 16) % limit;
    };
    let id = 0;
    const samples = ["Plain wording. ", "<&> ", "\u00a0", "漢字 😀 ", '"quoted" & \'apostrophe\' ', "\n\t", "£1,234.50 "];
    const inline = (): NodeJSON[] => Array.from({ length: 1 + random(8) }, () => {
      const marks = formats[random(formats.length)]!.map(type => ({ type }));
      return random(3) === 0
        ? { type: "generated_text", attrs: { id: `fact-${id++}`, text: random(5) === 0 ? "" : samples[random(samples.length)]! }, marks }
        : { ...text(samples[random(samples.length)]!), marks };
    });
    const list = (depth: number): NodeJSON => ({
      type: "ordered_list", attrs: { id: `list-${id++}`, order: [1, 3, 25, 0, -2][random(5)] },
      content: Array.from({ length: 1 + random(4) }, () => ({
        type: "list_item", attrs: { id: `item-${id++}` },
        content: [
          ...Array.from({ length: 1 + random(3) }, () => paragraph(null, ...inline())),
          ...(depth < 3 && random(2) === 0 ? [list(depth + 1)] : []),
        ],
      })),
    });
    const generated: NodeJSON[] = Array.from({ length: 30 }, () => random(3) === 0
      ? list(0) : paragraph(`paragraph-${id++}`, ...inline()));
    const current = structuredClone(generated);
    // Edits the reader could make: rewording around facts, and writing clauses of their own.
    const words = (): NodeJSON[] => inline().filter(node => node.type === "text");
    const edit = (nodes: NodeJSON[], userNode: () => NodeJSON): void => {
      for (const node of nodes) {
        if (node.type === "paragraph" && random(3) === 0) {
          node.content = node.content!.flatMap(child => child.type === "generated_text"
            ? [child]
            : random(4) === 0 ? [] : words());
        }
        if (node.type === "ordered_list") {
          edit(node.content!, () => ({ type: "list_item", attrs: { id: null }, content: [paragraph(null, ...words())] }));
        }
        if (node.type === "list_item") edit(node.content!, () => paragraph(null, ...words()));
      }
      if (random(3) === 0) nodes.splice(random(nodes.length), 0, userNode());
    };
    edit(current, () => paragraph(null, ...words()));
    cases.push({ name: `stress-${String(seed).padStart(2, "0")}`, snapshot: snapshot(current, generated) });
  }
  return cases;
}
