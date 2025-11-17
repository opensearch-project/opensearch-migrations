const command = process.argv[2];

// Remove the command from argv so the main functions see original args
process.argv.splice(2, 1);

(async () => {
    switch (command) {
        case 'constrainSchema':
            require('./constrainUserSchema').main();
            break;
        case 'makeSample':
            require('@opensearch-migrations/schemas/makeSample').main();
            break;
        case 'initialize':
            require('./runMigrationInitializer').main();
            break;
        case 'formatApprovals':
            require('./formatApprovals').main();
            break;
        case 'showSchema':
            require('@opensearch-migrations/schemas/showUserSchema').main();
            break;
        default:
            throw new Error(`Unknown command: ${command}` +
                '\nAvailable commands: constrainSchema, makeSample, initialize, showSchema');
    }
})().catch(err => {
        console.error(err);
        process.exit(2);
    });