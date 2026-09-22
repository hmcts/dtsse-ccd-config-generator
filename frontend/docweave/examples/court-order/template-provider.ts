import {
  parseTemplateFragment,
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
  // Title and wording, worked out from the content as the backend does.
  const searchable = new Map<string, string>();
  const index = (id: string, input: SaveTemplateInput): void => {
    const { document } = parseTemplateFragment(input.content);
    // Wording only, as the backend indexes it: a template's dates are not searched.
    const wording = document.textBetween(0, document.content.size, " ", () => "");
    searchable.set(id, `${input.title}\n${wording}`.toLocaleLowerCase());
  };

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
            searchable.get(template.id)?.includes(normalizedQuery)
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
      index(template.id, input);
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
      index(id, input);
      return copy(updated);
    },

    async delete(id, expectedRevision) {
      const current = find(id);
      if (current.revision !== expectedRevision) {
        throw conflict();
      }
      templates.delete(id);
      searchable.delete(id);
    },
  };
}
