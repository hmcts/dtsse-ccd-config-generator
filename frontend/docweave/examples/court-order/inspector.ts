import type { DocWeaveSnapshot } from "@hmcts-cft/docweave";

export interface Inspector {
  update(snapshot: DocWeaveSnapshot): void;
}

export function createInspector(root: ParentNode): Inspector {
  const current = root.querySelector<HTMLElement>("#current-document-json");
  const generated = root.querySelector<HTMLElement>(
    "#generated-document-json",
  );
  if (!current || !generated) {
    throw new Error("The document inspector is missing");
  }

  return {
    update(snapshot): void {
      current.textContent = JSON.stringify(snapshot.current, null, 2);
      generated.textContent = JSON.stringify(snapshot.generated, null, 2);
    },
  };
}
