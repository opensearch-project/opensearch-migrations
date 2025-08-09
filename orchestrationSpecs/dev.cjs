// dev.cjs
const fs = require('fs');
const { register: registerPaths } = require('tsconfig-paths');
const tsconfig = require('./tsconfig.json');

// make "resources/*" work at runtime
registerPaths({
    baseUrl: __dirname,
    paths: tsconfig.compilerOptions.paths
});

// compile TS on the fly (source maps included)
const { register } = require('esbuild-register/dist/node');
register();

// load .sh files as strings
require.extensions['.sh'] = function (module, filename) {
    const content = fs.readFileSync(filename, 'utf8');
    module._compile('module.exports = ' + JSON.stringify(content), filename);
};

// start your app from src
require('./src/index.ts');