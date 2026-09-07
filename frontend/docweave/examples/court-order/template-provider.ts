import {
  type SaveTemplateInput,
  type Template,
  type TemplateProvider,
} from "@hmcts-cft/docweave";

const PAGE_SIZE = 20;

function copy(template: Template): Template {
  return structuredClone(template);
}

export function createInMemoryTemplateProvider(): TemplateProvider {
  const templates = new Map<string, Template>();

  function find(id: string): Template {
    const template = templates.get(id);
    if (!template) throw new Error("Template not found.");
    return template;
  }

  return {
    async search(query, cursor) {
      const normalizedQuery = query.trim().toLocaleLowerCase();
      const matches = [...templates.values()]
        .filter((template) =>
          template.title.toLocaleLowerCase().includes(normalizedQuery)
        )
        .sort((left, right) =>
          right.updatedAt.localeCompare(left.updatedAt) ||
          right.id.localeCompare(left.id)
        );
      const [cursorUpdatedAt, cursorId] = cursor?.split("\n") ?? [];
      const cursorStart = cursor
        ? matches.findIndex((template) =>
          template.updatedAt < cursorUpdatedAt! ||
          (template.updatedAt === cursorUpdatedAt && template.id < cursorId!)
        )
        : 0;
      const start = cursorStart < 0 ? matches.length : cursorStart;
      const page = matches.slice(
        start,
        start + PAGE_SIZE + 1,
      );
      const items = page.slice(0, PAGE_SIZE);
      const last = items.at(-1);

      return {
        items: items.map(copy),
        nextCursor: page.length > PAGE_SIZE && last
          ? `${last.updatedAt}\n${last.id}`
          : undefined,
      };
    },

    async create(input: SaveTemplateInput) {
      const template: Template = {
        id: crypto.randomUUID(),
        title: input.title,
        revision: 1,
        updatedAt: new Date().toISOString(),
        content: structuredClone(input.content),
      };
      templates.set(template.id, template);
      return copy(template);
    },

    async update(id, input) {
      const current = find(id);
      if (current.revision !== input.expectedRevision) {
        throw new Error(
          "This template was changed elsewhere. Reload it before saving.",
        );
      }
      const updated: Template = {
        ...current,
        title: input.title,
        content: structuredClone(input.content),
        revision: current.revision + 1,
        updatedAt: new Date().toISOString(),
      };
      templates.set(id, updated);
      return copy(updated);
    },

    async delete(id, expectedRevision) {
      const current = find(id);
      if (current.revision !== expectedRevision) {
        throw new Error(
          "This template was changed elsewhere. Reload it before saving.",
        );
      }
      templates.delete(id);
    },
  };
}
