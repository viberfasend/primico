---
title: Write and preview the docs
description: Where a page goes, how to link it, and how to see the site locally before you push.
sidebar:
  order: 99
---

These pages are ordinary Markdown in the repository's `docs/` directory. The same file is read
two ways: on GitHub, next to the code, and on the documentation site that
[`docs-site/`](../../docs-site/) builds from it with [Starlight](https://starlight.astro.build).
Nothing is copied between the two, so nothing drifts.

## Preview locally

```bash
cd docs-site
npm ci
npm run dev      # http://localhost:4321/primico/docs/ — reloads as you edit docs/
npm run build    # the production build, then the link check CI runs
```

`npm run build` fails on a link to a page or a heading that does not exist. Fix the link rather
than the checker: a link that is broken on the site is broken on GitHub too, since both resolve
it against the file it is written in.

## Where a page goes

The site is organised by what the reader is trying to do — the
[Diátaxis](https://diataxis.fr/) split. Decide which of the four a page is before writing it;
a page that tries to be two of them is usually two pages.

| Directory | The reader wants to… | Shape |
|---|---|---|
| `tutorials/` | learn, by doing something end to end | numbered steps that always work, one path, no options |
| `how-to/` | get one job done they already understand | a goal in the title, prerequisites, steps, how to check it worked |
| `concepts/` | understand why it is built this way | prose and diagrams, the trade-offs, links out to the ADRs |
| `reference/` | look something up | tables and lists, complete and dry, mirrors the code's structure |
| `adr/` | know what was decided, when, and what it cost | one decision record per file, never rewritten after it is accepted |
| `plans/` | see what is next and why that order | planning notes, dated; superseded by ADRs and issues as work starts |

## Writing a page

Every page starts with front matter; the title is **not** repeated as a `#` heading, because the
site renders it for you.

```md
---
title: Add a column to a table
description: One sentence — shown in search results and link previews.
sidebar:
  order: 3        # position inside its group; lower comes first
---
```

- **Link with relative paths to the `.md` file**: `[sync](../concepts/sync.md)`,
  `[ADR 0004](../adr/0004-tags.md#1-a-tag-is-identity-only-membership-is-a-column-on-the-task)`.
  The site rewrites them to its own URLs.
- **Link to code with a relative path too**: `[CadenceRepository](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/CadenceRepository.kt)`.
  On the site it becomes a GitHub link on `main`.
- **Link issues absolutely**: `https://github.com/viberfasend/primico/issues/40`.
- **Diagrams are Mermaid** in a ` ```mermaid ` fence. GitHub and the site both render them. Draw
  the mechanism (who calls whom, in which order), not a box per module.
- **Asides** use Starlight's syntax, which GitHub shows as a plain paragraph:
  `:::note`, `:::tip`, `:::caution`, `:::danger`, each closed with `:::`.
- **Say what the code says.** Name the real class, file and test. A page that invents a name is
  worse than no page; when you are not sure, read the code before writing the sentence.

## Keeping pages true

A page is part of the change that makes it wrong. If a pull request renames a class, changes a
schema version, or moves a rule, it updates the page that names it — the same way it updates the
test. `CLAUDE.md` remains the dense, agent-facing version of the architecture notes; these pages
are the human-facing, navigable one, and when the two disagree, the code decides and both get
fixed.

ADRs are the exception: an accepted ADR is history. To change a decision, write a new ADR that
supersedes it and add a line to the old one pointing forward.
