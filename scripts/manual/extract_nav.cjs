#!/usr/bin/env node
/**
 * Extract the exact sidebar trees from docs-site/.vitepress/config.ts.
 * The sidebar blocks are plain JS object literals, so a brace-balanced slice
 * can be evaluated safely in Node without pulling in VitePress.
 */
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const configPath = path.join(root, 'docs-site/.vitepress/config.ts');
const source = fs.readFileSync(configPath, 'utf8');

function sliceObjectAfter(label, fromIndex = 0) {
  const labelIndex = source.indexOf(label, fromIndex);
  if (labelIndex < 0) throw new Error(`label not found: ${label}`);
  const start = source.indexOf('{', source.indexOf('sidebar', labelIndex));
  let depth = 0;
  let inString = null;
  for (let i = start; i < source.length; i += 1) {
    const ch = source[i];
    if (inString) {
      if (ch === '\\') i += 1;
      else if (ch === inString) inString = null;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') inString = ch;
    else if (ch === '{') depth += 1;
    else if (ch === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  throw new Error('unbalanced object');
}

const zhStart = source.lastIndexOf('sidebar: {');
const enStart = source.indexOf("'/en/guide/'");
const zh = eval(`(${sliceObjectAfter('sidebar: {', zhStart)})`); // eslint-disable-line no-eval
const en = eval(`(${sliceObjectAfter('sidebar: {', enStart)})`); // eslint-disable-line no-eval

const outDir = path.join(root, 'manual-build');
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'nav-zh.json'), JSON.stringify(zh, null, 2));
fs.writeFileSync(path.join(outDir, 'nav-en.json'), JSON.stringify(en, null, 2));
console.log('zh groups:', Object.keys(zh), 'en groups:', Object.keys(en));
