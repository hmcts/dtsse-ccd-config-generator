import {
  TemplateRequestError,
  type SaveTemplateInput,
  type Template,
  type TemplateProvider,
} from "@hmcts-cft/docweave";

function copy(template: Template): Template {
  return structuredClone(template);
}

function conflict(): TemplateRequestError {
  return new TemplateRequestError(
    "This template was changed elsewhere. Reload it before saving.",
    409,
  );
}

export function createInMemoryTemplateProvider(): TemplateProvider {
  const templates = new Map<string, Template>();

  function find(id: string): Template {
    const template = templates.get(id);
    if (!template) throw new TemplateRequestError("Template not found.", 404);
    return template;
  }

  return {
    async search(query) {
      const normalizedQuery = query.trim().toLocaleLowerCase();
      return {
        items: [...templates.values()]
          .filter((template) =>
            template.title.toLocaleLowerCase().includes(normalizedQuery)
          )
          .sort((left, right) =>
            left.title.localeCompare(right.title) ||
            left.id.localeCompare(right.id)
          )
          .map(copy),
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
        throw conflict();
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
        throw conflict();
      }
      templates.delete(id);
    },
  };
}
