/**
 * Build script for TypeScript transforms and test cases.
 *
 * Finds all *.transform.ts files, bundles each with esbuild,
 * and wraps the output in the GraalVM closure format:
 *   (function(bindings) { return function(msg) { ... }; })
 *
 * Also finds all *.testcase.ts files, bundles them, and extracts
 * the exported testCases array into a JSON manifest.
 *
 * Usage:
 *   node build.mjs           # One-shot build
 *   node build.mjs --watch   # Watch mode — rebuilds on file changes
 */
import { build, context } from 'esbuild';
import { writeFileSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';

const SRC_DIR = 'src';
const DIST_DIR = 'dist';
const watchMode = process.argv.includes('--watch');

/** Recursively find files matching a suffix under a directory. */
function findFiles(dir, suffix) {
  const results = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      results.push(...findFiles(full, suffix));
    } else if (entry.endsWith(suffix)) {
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
            .replaceAll(/^export\s*\{[^}]*\};\s*$/gm, '')
            .trim();
          const wrapped = `(function(bindings) {\n${code}\nreturn transform;\n})`;
          mkdirSync(dirname(file.path), { recursive: true });
          writeFileSync(file.path, wrapped);
        }
      });
    },
  };
}

/** esbuild plugin that extracts testCases from bundled output into JSON. */
function testCaseExtractPlugin() {
  return {
    name: 'testcase-extract',
    setup(build) {
      build.onEnd((result) => {
        if (result.errors.length > 0) return;
        for (const file of result.outputFiles || []) {
          // The bundled output defines testCases as a var. Evaluate it.
          let code = file.text
            .replaceAll(/^export\s*\{[^}]*\};\s*$/gm, '')
            .trim();
          // Wrap in a function to extract the testCases variable
          const fn = new Function(code + '\nreturn testCases;');
          const cases = fn();
          mkdirSync(dirname(file.path), { recursive: true });
          writeFileSync(file.path, JSON.stringify(cases, null, 2));
        }
      });
    },
  };
}

// Build transforms
const transforms = findFiles(SRC_DIR, '.transform.ts');
for (const entry of transforms) {
  const rel = relative(SRC_DIR, entry)
    .replace(/\.transform\.ts$/, '.js')
    .replaceAll('/', '-');
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

// Build test cases → JSON
const testCaseFiles = findFiles(SRC_DIR, '.testcase.ts');
for (const entry of testCaseFiles) {
  const rel = relative(SRC_DIR, entry)
    .replace(/\.testcase\.ts$/, '.testcases.json')
    .replaceAll('/', '-');
  const outPath = join(DIST_DIR, rel);

  const opts = {
    entryPoints: [entry],
    bundle: true,
    write: false,
    format: 'esm',
    target: 'es2020',
    treeShaking: true,
    outfile: outPath,
    plugins: [testCaseExtractPlugin()],
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

const total = transforms.length + testCaseFiles.length;
console.log(watchMode ? `Watching ${total} file(s) for changes...` : 'Done.');
