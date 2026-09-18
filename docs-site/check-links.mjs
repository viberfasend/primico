// Fails the build when a page links to another page, an anchor, or a file in the repository that
// does not exist. Runs over the built HTML, so it sees links after src/plugins/repo-links.mjs has
// rewritten them — which also catches a relative link that is wrong on GitHub, since both resolve
// it the same way. A link into the code becomes a GitHub URL on `main`, and is checked against
// this checkout, so renaming a file without fixing the page that names it fails here.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import path from 'node:path';

const dist = new URL('./dist/', import.meta.url).pathname;
const base = '/primico/docs';
const repo = 'https://github.com/viberfasend/primico/';
const root = new URL('../', import.meta.url).pathname;
const pages = [];
(function walk(dir) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.html')) pages.push(p);
  }
})(dist);

const idsOf = new Map();
const ids = (file) => {
  if (!idsOf.has(file)) {
    idsOf.set(file, new Set([...readFileSync(file, 'utf8').matchAll(/\sid="([^"]+)"/g)].map((m) => m[1])));
  }
  return idsOf.get(file);
};

let broken = 0;
for (const page of pages) {
  const html = readFileSync(page, 'utf8');
  const main = html.match(/<main[\s\S]*<\/main>/)?.[0] ?? '';
  for (const [, raw] of main.matchAll(/<a\b[^>]*\shref="([^"]+)"/g)) {
    const href = raw.replaceAll('&amp;', '&');
    let target, hash;
    if (href.startsWith('#')) { target = page; hash = href.slice(1); }
    else if (href.startsWith(base + '/')) {
      const [p, h] = href.slice(base.length).split('#');
      target = path.join(dist, decodeURI(p), p.endsWith('/') ? 'index.html' : '');
      hash = h;
    } else if (href.startsWith(repo + 'blob/main/') || href.startsWith(repo + 'tree/main/')) {
      const file = decodeURI(href.slice(repo.length).split('#')[0].replace(/^(blob|tree)\/main\//, ''));
      if (!existsSync(path.join(root, file))) {
        broken++;
        console.error(`${path.relative(dist, page)}: no such file in the repository: ${file}`);
      }
      continue;
    } else if (!/^[a-z]+:/i.test(href)) {
      broken++;
      console.error(`${path.relative(dist, page)}: link was not rewritten: ${href}`);
      continue;
    } else continue;
    const ok = existsSync(target) && (!hash || ids(target).has(decodeURIComponent(hash)));
    if (!ok) {
      broken++;
      console.error(`${path.relative(dist, page)}: broken link ${href}`);
    }
  }
}
if (broken) { console.error(`\n${broken} broken link(s).`); process.exit(1); }
console.log(`Links OK across ${pages.length} pages.`);
