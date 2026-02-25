/**
 * Build script for TypeScript transforms.
 *
 * Finds all *.transform.ts files, bundles each with esbuild,
 * and wraps the output in the GraalVM closure format:
 *   (function(bindings) { return function(msg) { ... }; })
 *
 * Usage:
 *   node build.mjs           # One-shot build
 *   node build.mjs --watch   # Watch mode — rebuilds on file changes
 */
import { build, context } from 'esbuild';
import { writeFileSync, mkdirSync, readdirSync, statSync } from 'fs';
import { dirname, join, relative } from 'path';

const SRC_DIR = 'src';
const DIST_DIR = 'dist';
const watchMode = process.argv.includes('--watch');

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

/** esbuild plugin that wraps output in GraalVM closure format. */
function graalvmWrapPlugin() {
  return {
    name: 'graalvm-wrap',
    setup(build) {
      build.onEnd((result) => {
        if (result.errors.length > 0) return;
        for (const file of result.outputFiles || []) {
          let code = file.text
            .replace(/^export\s*\{[^}]*\};\s*$/gm, '')
            .trim();
          const wrapped = `(function(bindings) {\n${code}\nreturn transform;\n})`;
          mkdirSync(dirname(file.path), { recursive: true });
          writeFileSync(file.path, wrapped);
        }
      });
    },
  };
}

const transforms = findTransforms(SRC_DIR);

if (transforms.length === 0) {
  console.log('No *.transform.ts files found.');
  process.exit(0);
}

// Build each transform as a separate entry → output
for (const entry of transforms) {
  const rel = relative(SRC_DIR, entry)
    .replace(/\.transform\.ts$/, '.js')
    .replace(/\//g, '-');
  const outPath = join(DIST_DIR, rel);

  const opts = {
    entryPoints: [entry],
    bundle: true,
    write: false,
    format: 'esm',
    target: 'es2020',
    treeShaking: true,
    outfile: outPath,
    plugins: [graalvmWrapPlugin()],
  };

  if (watchMode) {
    const ctx = await context(opts);
    await ctx.watch();
    console.log(`  Watching ${entry} → ${outPath}`);
  } else {
    await build(opts);
    console.log(`  ${entry} → ${outPath}`);
  }
}

console.log(watchMode ? `Watching ${transforms.length} transform(s) for changes...` : 'Done.');
