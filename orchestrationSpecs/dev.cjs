// dev.cjs
const fs = require('fs');

// Register the .sh extension handler FIRST
require.extensions['.sh'] = (module, filename) => {
    const content = fs.readFileSync(filename, 'utf8');
    module._compile('module.exports = ' + JSON.stringify(content), filename);
};

// Register tsx for TypeScript support
require('tsx/cjs/api').register();

// Load tsconfig paths
require('tsconfig-paths/register');

// Now require your main file
require('./src/index.ts');