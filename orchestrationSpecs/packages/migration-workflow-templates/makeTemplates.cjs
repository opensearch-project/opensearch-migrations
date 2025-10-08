const fs = require('fs');

// Register tsx for TypeScript support
require('tsx/cjs/api').register();

// Load tsconfig paths
require('tsconfig-paths/register');

// Now require your main file
require('./src/makeTemplates.ts');
