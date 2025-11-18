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

function removeBinDirs(directory) {
    const items = fs.readdirSync(directory, { withFileTypes: true });

    for (const item of items) {
        const fullPath = path.join(directory, item.name);

        if (item.isDirectory()) {
            if (item.name === '.bin') {
                fs.rmSync(fullPath, { recursive: true });
                console.log(`Removed .bin directory: ${fullPath}`);
            } else if (item.name) {
                // Recursively check nested node_modules
                removeBinDirs(fullPath);
            }
        }
    }
}

async function bundle() {
    console.log('Bundling with esbuild...');

    // Accept output directory as command line argument
    const outputDir = process.argv[2] || path.join(__dirname, 'bundled');
    console.log(`Output directory: ${outputDir}`);

    if (fs.existsSync(outputDir)) {
        fs.rmSync(outputDir, { recursive: true });
    }
    fs.mkdirSync(outputDir, { recursive: true });

    const outputFile = path.join(outputDir, 'index.js');

    await esbuild.build({
        entryPoints: ['dist/cliRouter.js'],
        bundle: true,
        platform: 'node',
        target: 'node22',
        outfile: outputFile,
        external: [
            '@grpc/grpc-js',
            '@grpc/proto-loader',
            'etcd3'
        ],
        banner: {
            js: '#!/usr/bin/env node\nprocess.env.SUPPRESS_AUTO_LOAD = "true";'
        }
    });

    console.log('Bundle created (excluding external modules)');

    // Find all dependencies recursively
    const workspaceNodeModules = path.join(__dirname, '../../node_modules');
    const bundledNodeModules = path.join(outputDir, 'node_modules');
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
            console.log(`Copied ${dep}`);
        }
    }

    console.log('\nRemoving .bin directories...');
    removeBinDirs(bundledNodeModules);

    // Copy shell scripts
    const scriptsDir = path.join(__dirname, 'scripts');
    if (fs.existsSync(scriptsDir)) {
        const shellScripts = fs.readdirSync(scriptsDir).filter(f => f.endsWith('.sh'));
        for (const script of shellScripts) {
            const src = path.join(scriptsDir, script);
            const dest = path.join(outputDir, script);
            fs.copyFileSync(src, dest);
            fs.chmodSync(dest, 0o755);  // Make executable
            console.log(`Copied ${script}`);
        }
    }

    // Make the bundle executable
    fs.chmodSync(outputFile, 0o755);

    console.log('\nBundle complete!');
    console.log(`Output: ${outputDir}`);
    console.log(`Total dependencies: ${allDeps.size}`);
}

bundle().catch(err => {
    console.error('Bundle failed:', err);
    process.exit(1);
});