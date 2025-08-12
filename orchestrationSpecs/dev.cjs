require('ts-node').register({
    transpileOnly: false, // Do full type-checking!
    files: true           // for .d.ts are picked up
});

require('tsconfig-paths/register');

const fs = require('fs');
require.extensions['.sh'] = (module, filename) => {
    const content = fs.readFileSync(filename, 'utf8');
    module._compile('module.exports = ' + JSON.stringify(content), filename);
};

require('./src/index.ts');
