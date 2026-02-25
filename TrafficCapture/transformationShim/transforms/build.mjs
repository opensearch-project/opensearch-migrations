/**
 * Build script for TypeScript transforms.
 *
 * Finds all *.transform.ts files, bundles each with esbuild,
 * and wraps the output in the GraalVM closure format:
 *   (function(bindings) { return function(msg) { ... }; })
 *
 * Usage: node build.mjs
 */
import { buildSync } from 'esbuild';
import { writeFileSync, mkdirSync, readdirSync, statSync } from 'fs';
import { dirname, join, relative } from 'path';

const SRC_DIR = 'src';
const DIST_DIR = 'dist';

/** Recursively find all *.transform.ts files under a directory. */
function findTransforms(dir) {
  const results = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      results.push(...findTransforms(full));
    } else if (entry.endsWith('.transform.ts')) {
      results.push(full);
    }
  }
  return results;
}

const transforms = findTransforms(SRC_DIR);

if (transforms.length === 0) {
  console.log('No *.transform.ts files found.');
  process.exit(0);
}

console.log(`Building ${transforms.length} transform(s)...`);

for (const entry of transforms) {
  // src/solr-to-opensearch/request.transform.ts → dist/solr-to-opensearch-request.js
  const rel = relative(SRC_DIR, entry)
    .replace(/\.transform\.ts$/, '.js')
    .replace(/\//g, '-');
  const outPath = join(DIST_DIR, rel);

  const result = buildSync({
    entryPoints: [entry],
    bundle: true,
    write: false,
    format: 'esm',
    target: 'es2020',
    treeShaking: true,
  });

  // Strip ESM export statement and wrap in GraalVM closure format
  let code = result.outputFiles[0].text;
  code = code.replace(/^export\s*\{[^}]*\};\s*$/gm, '').trim();

  const wrapped = `(function(bindings) {\n${code}\nreturn transform;\n})`;

  mkdirSync(dirname(outPath), { recursive: true });
  writeFileSync(outPath, wrapped);
  console.log(`  ${entry} → ${outPath}`);
}

console.log('Done.');
