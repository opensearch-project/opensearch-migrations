// buildScripts/clean-artifacts.js
const fs = require('fs');
const p = require('path');

const DELETE_DIRS = new Set([
    'dist','build','coverage','.turbo','.parcel-cache','.cache','.vite',
    '.jest','.jest-cache','.ts-node','.nyc_output'
]);

const shouldDeleteFile = (name) =>
    name.endsWith('.tsbuildinfo') ||
    name === '.eslintcache';

function rm(target) {
    try { fs.rmSync(target, { recursive: true, force: true }); } catch {}
}

function walk(dir) {
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
        const fp = p.join(dir, ent.name);
        if (ent.isDirectory()) {
            if (ent.name === 'node_modules' || ent.name.startsWith('.git')) continue;
            if (DELETE_DIRS.has(ent.name)) rm(fp);
            else walk(fp);
        } else if (ent.isFile()) {
            if (shouldDeleteFile(ent.name)) rm(fp);
        }
    }
}

walk(process.cwd());
// also clear node_modules/.cache if present
rm(p.join(process.cwd(), 'node_modules', '.cache'));
