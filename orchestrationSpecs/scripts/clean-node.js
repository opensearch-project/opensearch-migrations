#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

function rm(dir) {
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch {}
}

function walk(dir) {
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, ent.name);
        if (ent.isDirectory()) {
            if (ent.name === 'node_modules') {
                rm(p);
                // do not recurse into deleted dir
                continue;
            }
            // skip traversing restored node_modules
            if (ent.name !== 'node_modules') walk(p);
        }
    }
}

rm(path.join(process.cwd(), 'node_modules')); // root
walk(process.cwd());                           // nested
