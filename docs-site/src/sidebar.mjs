// Builds a sidebar group's items from one directory of docs/.
//
// Starlight's own `autogenerate` matches pages by their path under src/content/docs/, and ours
// live in the repository's docs/ instead (see src/content.config.ts), so it finds nothing and
// every group renders as an empty heading. This reads the same front matter it would — `title`,
// `sidebar.label`, `sidebar.order` — and returns plain links, README (the section's overview)
// first.
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

export function sidebarItems(docsDir, directory) {
  const dir = path.join(docsDir, directory);
  return readdirSync(dir)
    .filter((f) => /\.mdx?$/.test(f))
    .map((file) => {
      const fm = frontMatter(readFileSync(path.join(dir, file), 'utf8'));
      const slug = file === 'README.md' ? '' : `${file.replace(/\.mdx?$/, '').toLowerCase()}/`;
      return {
        label: fm.label ?? fm.title ?? file,
        link: `/${directory}/${slug}`,
        order: file === 'README.md' ? -1 : (fm.order ?? Number.MAX_SAFE_INTEGER),
        file,
      };
    })
    .sort((a, b) => a.order - b.order || a.file.localeCompare(b.file))
    .map(({ label, link }) => ({ label, link }));
}

// Just enough YAML for the keys above: top-level `title`, and `label`/`order` under `sidebar`.
function frontMatter(source) {
  const block = source.match(/^---\n([\s\S]*?)\n---/)?.[1] ?? '';
  const unquote = (v) => v.trim().replace(/^(["'])(.*)\1$/, '$2');
  const out = {};
  let inSidebar = false;
  for (const line of block.split('\n')) {
    const top = line.match(/^([a-zA-Z]+):\s*(.*)$/);
    if (top) {
      inSidebar = top[1] === 'sidebar';
      if (top[1] === 'title') out.title = unquote(top[2]);
      continue;
    }
    const nested = inSidebar && line.match(/^\s+(label|order):\s*(.*)$/);
    if (nested) out[nested[1]] = nested[1] === 'order' ? Number(nested[2]) : unquote(nested[2]);
  }
  return out;
}
