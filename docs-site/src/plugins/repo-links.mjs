// Rewrites the relative links in docs/ so one Markdown file works in two places.
//
// On GitHub a link like `../adr/0005-neon-sync.md` or `../../core/build.gradle.kts` is resolved
// against the file itself, which is exactly right. On the site it is not: pages are served at
// `/primico/docs/adr/0005-neon-sync/`, and a file outside docs/ is not served at all. This plugin
// resolves each relative href against the page's source path and then
//   - turns a link to another page (`*.md`, `*.mdx`) into that page's site URL, and
//   - turns a link to anything else in the repository into its GitHub URL on `main`.
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = 'https://github.com/viberfasend/primico';

// A Sätteri hast plugin factory: Astro calls it once per file with that file's URL.
export default function repoLinks({ docsDir, base }) {
  const root = path.resolve(docsDir, '..');
  return ({ fileURL }) => {
    const source = fileURL && fileURLToPath(fileURL);
    if (!source || !path.resolve(source).startsWith(path.resolve(docsDir) + path.sep)) return;
    return {
      name: 'primico-repo-links',
      element: [{
        filter: ['a'],
        visit(node, ctx) {
          const href = node.properties?.href;
          if (typeof href !== 'string') return;
          const rewritten = rewrite(href, source, docsDir, root, base);
          if (rewritten) ctx.setProperty(node, 'href', rewritten);
        },
      }],
    };
  };
}

function rewrite(href, source, docsDir, root, base) {
  if (/^([a-z]+:|#|\/)/i.test(href)) return;
  const [target, hash = ''] = href.split('#');
  if (!target) return;
  const resolved = path.resolve(path.dirname(source), decodeURI(target));
  const fromDocs = path.relative(docsDir, resolved);
  const anchor = hash ? `#${hash}` : '';
  const inDocs = !fromDocs.startsWith('..') && !fromDocs.startsWith('agents');
  if (inDocs && /\.mdx?$/.test(fromDocs)) {
    const slug = fromDocs
      .replace(/\.mdx?$/, '')
      .replace(/(^|\/)README$/, '$1')
      .replace(/(^|\/)index$/, '$1')
      .replace(/\/$/, '')
      .toLowerCase();
    return `${base}/${slug ? `${slug}/` : ''}${anchor}`;
  }
  const fromRoot = path.relative(root, resolved).split(path.sep).join('/');
  // `../../issues/40` style links lean on GitHub resolving them against the blob URL.
  const issue = fromRoot.match(/^(?:\.\.\/)*(issues|pull|milestones)(?:\/(\d+))?$/);
  if (issue) return `${REPO}/${issue[1]}${issue[2] ? `/${issue[2]}` : ''}${anchor}`;
  const kind = /\.[a-z0-9]+$/i.test(fromRoot) ? 'blob' : 'tree';
  return `${REPO}/${kind}/main/${fromRoot}${anchor}`;
}
