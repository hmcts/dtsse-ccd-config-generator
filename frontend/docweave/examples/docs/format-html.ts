const BLOCK_TAGS = new Set(["P", "H1", "H2", "H3", "H4", "H5", "H6", "OL", "UL", "LI"]);

function formatNode(node: Node, depth: number, lines: string[]): void {
  const indent = "  ".repeat(depth);
  if (!(node instanceof node.ownerDocument!.defaultView!.Element)) {
    lines.push(indent + (node.textContent ?? ""));
    return;
  }
  const element = node;
  const tag = element.tagName.toLowerCase();
  const attributes = [...element.attributes]
    .map((attribute) => ` ${attribute.name}="${attribute.value}"`)
    .join("");
  const hasBlockChildren = [...element.children].some((child) =>
    BLOCK_TAGS.has(child.tagName)
  );
  if (!hasBlockChildren) {
    lines.push(`${indent}<${tag}${attributes}>${element.innerHTML}</${tag}>`);
    return;
  }
  lines.push(`${indent}<${tag}${attributes}>`);
  for (const child of element.childNodes) {
    if (child.nodeType === 3 && !child.textContent?.trim()) continue;
    formatNode(child, depth + 1, lines);
  }
  lines.push(`${indent}</${tag}>`);
}

/** Puts each block element on its own line, indented; inline markup stays as is. */
export function formatHtml(html: string, document: Document): string {
  const container = document.createElement("div");
  container.innerHTML = html;
  const lines: string[] = [];
  for (const child of container.childNodes) formatNode(child, 0, lines);
  return lines.join("\n");
}
