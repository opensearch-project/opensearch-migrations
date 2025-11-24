import { Readable } from 'stream';
import { z } from 'zod';
import {MigrationConfigTransformer} from "./migrationConfigTransformer";

async function main() {
    const args = process.argv.slice(2);

    if (args.includes('--help') || args.includes('-h')) {
        console.log(`
Usage: process-config [input-file]

Process and validate migration configuration files.

Arguments:
  input-file    Path to input JSON file ('-' for stdin')
`);
        process.exit(0);
    }

    const processor = new MigrationConfigTransformer();

    try {
        let stream: Readable;

        if (args.length === 0) {
            console.log("Must supply an input filename or '-' to read from stdin.");
            process.exit(0);
        } else if (args[0] === '-') {
            // Read from stdin (default behavior)
            stream = process.stdin;
        } else {
            // Read from file
            const fs = await import('fs');
            stream = fs.createReadStream(args[0], 'utf-8');
        }

        const result = await processor.processFromStream(stream);
        console.log(JSON.stringify(result, null, 2));
        process.exit(0);
    } catch (error) {
        if (error instanceof z.ZodError) {
            console.error('Validation error:');
            console.error(JSON.stringify(error.issues, null, 2));
            process.exit(1);
        } else if (error instanceof Error) {
            console.error('Error:', error.message);
            process.exit(1);
        } else {
            console.error('Unknown error:', error);
            process.exit(1);
        }
    }
}

// Run if executed directly
if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch(error => {
        console.error('Fatal error:', error);
        process.exit(1);
    });
}

