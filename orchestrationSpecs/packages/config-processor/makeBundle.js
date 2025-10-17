const esbuild = require('esbuild');
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

function getDependencies(packageName, workspaceNodeModules, visited = new Set()) {
    if (visited.has(packageName)) return [];
    visited.add(packageName);

    const pkgPath = path.join(workspaceNodeModules, packageName, 'package.json');
    if (!fs.existsSync(pkgPath)) return [];

    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
    const deps = Object.keys(pkg.dependencies || {});

    let allDeps = [packageName];
    for (const dep of deps) {
        allDeps = allDeps.concat(getDependencies(dep, workspaceNodeModules, visited));
    }

    return allDeps;
}

async function bundle() {
    console.log('Bundling with esbuild...');

    const bundledDir = path.join(__dirname, 'bundled');
    if (fs.existsSync(bundledDir)) {
        fs.rmSync(bundledDir, { recursive: true });
    }
    fs.mkdirSync(bundledDir, { recursive: true });

    await esbuild.build({
        entryPoints: ['dist/RunMigrationInitializer.js'],
        bundle: true,
        platform: 'node',
        target: 'node18',
        outfile: 'bundled/index.js',
        external: [
            '@grpc/grpc-js',
            '@grpc/proto-loader',
            'etcd3'
        ],
        banner: {
            js: '#!/usr/bin/env node'
        }
    });

    console.log('Bundle created');

    // Find all dependencies recursively
    const workspaceNodeModules = path.join(__dirname, '../../node_modules');
    const bundledNodeModules = path.join(bundledDir, 'node_modules');
    fs.mkdirSync(bundledNodeModules, { recursive: true });

    const topLevelExternals = ['etcd3', '@grpc/grpc-js', '@grpc/proto-loader'];
    const allDeps = new Set();

    for (const dep of topLevelExternals) {
        const deps = getDependencies(dep, workspaceNodeModules);
        deps.forEach(d => allDeps.add(d));
    }

    console.log(`Found ${allDeps.size} dependencies to copy (including transitive deps)`);

    // Copy all dependencies
    for (const dep of allDeps) {
        const src = path.join(workspaceNodeModules, dep);
        const dest = path.join(bundledNodeModules, dep);

        if (fs.existsSync(src)) {
            fs.mkdirSync(path.dirname(dest), { recursive: true });
            execSync(`cp -r "${src}" "${dest}"`);
            console.log(`Copied ${dep} to ${dest}`);
        }
    }

    console.log('\nBundle complete!');
    console.log(`Total dependencies: ${allDeps.size}`);
}

bundle().catch(err => {
    console.error('Bundle failed:', err);
    process.exit(1);
});