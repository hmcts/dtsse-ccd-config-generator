const svgNamespace = "http://www.w3.org/2000/svg";

const undoPaths = [
  "M9 14 4 9l5-5",
  "M4 9h10.5a5.5 5.5 0 0 1 0 11H11",
];

const redoPaths = [
  "M15 14 20 9l-5-5",
  "M20 9H9.5a5.5 5.5 0 0 0 0 11H13",
];

function createIcon(
  ownerDocument: Document,
  className: string,
  paths: readonly string[],
): SVGElement {
  const icon = ownerDocument.createElementNS(svgNamespace, "svg");

  icon.setAttribute("class", className);
  icon.setAttribute("viewBox", "0 0 24 24");
  icon.setAttribute("fill", "none");
  icon.setAttribute("stroke", "currentColor");
  icon.setAttribute("stroke-width", "2.5");
  icon.setAttribute("stroke-linecap", "round");
  icon.setAttribute("stroke-linejoin", "round");
  icon.setAttribute("focusable", "false");
  icon.setAttribute("aria-hidden", "true");

  for (const path of paths) {
    const segment = ownerDocument.createElementNS(svgNamespace, "path");
    segment.setAttribute("d", path);
    icon.append(segment);
  }

  return icon;
}

export function createUndoIcon(
  ownerDocument: Document,
  className: string,
): SVGElement {
  return createIcon(ownerDocument, className, undoPaths);
}

export function createRedoIcon(
  ownerDocument: Document,
  className: string,
): SVGElement {
  return createIcon(ownerDocument, className, redoPaths);
}
