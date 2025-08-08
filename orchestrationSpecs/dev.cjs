const fs = require('fs');
const path = require('path');

// 1) Make Node understand your "fileResources/*" alias at runtime
const { register: registerPaths } = require('tsconfig-paths');
const tsconfig = require('./tsconfig.json');
registerPaths({
    baseUrl: __dirname,
    paths: tsconfig.compilerOptions.paths
});

// 2) Register esbuild for TS/TSX files (no custom loader here)
const { register } = require('esbuild-register/dist/node');
register({
    // leave loader alone; transform() only accepts a string,
    // and esbuild-register will pick the loader from the file ext.
});

// 3) Add a require hook for .sh so `import ".../*.sh"` returns a string
require.extensions['.sh'] = function (module, filename) {
    const content = fs.readFileSync(filename, 'utf8');
    module._compile('module.exports = ' + JSON.stringify(content), filename);
};

// 4) Run your TS entry
require('./src/index.ts');
