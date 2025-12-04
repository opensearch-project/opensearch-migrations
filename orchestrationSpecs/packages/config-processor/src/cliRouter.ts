const command = process.argv[2];

// Remove the command from argv so the main functions see original args
process.argv.splice(2, 1);

(async () => {
    const commands = new Map<string, () => Promise<void>>([
        ['constrainSchema', async () => require('./constrainUserSchema').main()],
        ['initialize', async () => require('./runMigrationInitializer').main()],
        ['findSecrets', async () => require('./findSecrets').main()],
        ['formatApprovals', async () => require('./formatApprovals').main()],

        ['makeSample', async () => require('@opensearch-migrations/schemas/makeSample').main()],
        ['showSchema', async () => require('@opensearch-migrations/schemas/showUserSchema').main()],
    ]);

    const handler = commands.get(command);
    if (!handler) {
        console.error(`Unknown command: ${command}` +
            `\nAvailable commands: ${Array.from(commands.keys()).join(', ')}`);
        process.exit(2);
    }

    await handler();
})().catch(err => {
        console.error(err);
        process.exit(2);
    });