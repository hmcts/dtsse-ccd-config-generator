-- A template is found by its title, its wording and its tags. The wording used to be
-- whatever text the client chose to send alongside the content, so a client that sent
-- none left a template findable by its title alone. Derive it from the stored content
-- instead: every ProseMirror text node, wherever it sits in the document.
create function docweave.template_searchable_text(title text, content jsonb, tags text[])
returns text
language sql
immutable
as $$
  select title
    || chr(10)
    || coalesce((
      select string_agg(node ->> 'text', ' ')
      from jsonb_path_query(content, 'strict $.** ? (@.type == "text")') node
    ), '')
    || chr(10)
    || array_to_string(tags, ' ')
$$;

-- Replacing the column recomputes it for every existing template.
alter table docweave.docweave_template drop column searchable_text;

alter table docweave.docweave_template
    add column searchable_text text not null
    generated always as (docweave.template_searchable_text(title, content, tags)) stored;
