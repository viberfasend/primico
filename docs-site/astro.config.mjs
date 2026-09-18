// @ts-check
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import mermaid from 'astro-mermaid';
import { satteri } from '@astrojs/markdown-satteri';
import repoLinks from './src/plugins/repo-links.mjs';

const base = '/primico/docs';
const docsDir = fileURLToPath(new URL('../docs', import.meta.url));

// The site is served next to the landing page: `site/` is the root of the Pages artefact and
// this build lands in `site/docs/` (see .github/workflows/pages.yml).
export default defineConfig({
  site: 'https://viberfasend.github.io',
  base,
  trailingSlash: 'always',
  markdown: {
    processor: satteri({ hastPlugins: [repoLinks({ docsDir, base })] }),
  },
  integrations: [
    mermaid({ theme: 'neutral', autoTheme: true }),
    starlight({
      title: 'Primico',
      description: 'Developer documentation for Primico, the local-first todo app for Android and the desktop.',
      logo: { src: './src/assets/logo.svg', replacesTitle: false },
      favicon: '/favicon.svg',
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/viberfasend/primico' }],
      editLink: { baseUrl: 'https://github.com/viberfasend/primico/edit/main/docs/' },
      lastUpdated: true,
      // Starlight only runs its Markdown transforms (asides, heading anchors…) on files inside
      // its own content directory unless told otherwise; ours is the repository's docs/.
      markdown: { processedDirs: ['../docs'] },
      customCss: ['./src/styles/theme.css'],
      sidebar: [
        { label: 'Start here', items: [
          { label: 'Overview', link: '/' },
          { autogenerate: { directory: 'tutorials' } },
        ] },
        { label: 'How-to guides', items: [
          { autogenerate: { directory: 'how-to' } },
          { label: 'Self-host sync', link: '/self-hosting/' },
        ] },
        { label: 'Concepts', items: [{ autogenerate: { directory: 'concepts' } }] },
        { label: 'Reference', items: [{ autogenerate: { directory: 'reference' } }] },
        { label: 'Decisions (ADRs)', items: [{ autogenerate: { directory: 'adr' } }] },
        { label: 'Plans & design notes', collapsed: true, items: [
          { autogenerate: { directory: 'plans' } },
          { label: 'Attachments and the share sheet', link: '/attachments-and-share/' },
        ] },
      ],
    }),
  ],
});
