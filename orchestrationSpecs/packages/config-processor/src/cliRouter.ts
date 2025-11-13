#!/usr/bin/env node

const command = process.argv[2];

// Remove the command from argv so the main functions see original args
process.argv.splice(2, 1);

switch(command) {
    case 'constrainSchema':
        require('./constrainUserSchema');
        break;
    case 'makeSample':
        require('@opensearch-migrations/schemas/makeSample');
        break;
    case 'initialize':
        require('./RunMigrationInitializer');
        break;
    case 'showSchema':
        require('@opensearch-migrations/schemas/showUserSchema');
        break;
    default:
        console.error(`Unknown command: ${command}`);
        console.error('Available commands: constrainSchema, makeSample, initialize, showSchema');
        process.exit(1);
}