import { defineCollection } from 'astro:content';
import { glob } from 'astro/loaders';
import { docsSchema } from '@astrojs/starlight/schema';

// The pages live in the repository's `docs/` directory, not under `src/content/docs/`, so that
// every one of them is also an ordinary Markdown file on GitHub, next to the code it describes.
// `docs/agents/` is guidance for coding agents, not for people, and stays off the site.
// A `README.md` is the index of its directory — GitHub shows it when you open the folder, and
// the site serves it at the folder's own URL.
export const collections = {
  docs: defineCollection({
    loader: glob({
      base: '../docs',
      pattern: ['**/*.{md,mdx}', '!agents/**'],
      generateId: ({ entry }) =>
        entry
          .replace(/\.mdx?$/, '')
          .replace(/(^|\/)README$/, '$1index')
          .replace(/\/index$/, '')
          .toLowerCase(),
    }),
    schema: docsSchema(),
  }),
};
