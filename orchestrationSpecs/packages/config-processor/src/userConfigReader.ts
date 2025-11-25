import fs from "node:fs";
import {parse} from "yaml";
import {OVERALL_MIGRATION_CONFIG} from "@opensearch-migrations/schemas";
import {z} from "zod";

async function readStdin(): Promise<string> {
    return new Promise((resolve, reject) => {
        const chunks: Buffer[] = [];

        process.stdin.on('data', (chunk) => {
            chunks.push(chunk);
        });

        process.stdin.on('end', () => {
            resolve(Buffer.concat(chunks).toString('utf-8'));
        });

        process.stdin.on('error', (error) => {
            reject(error);
        });
    });
}

async function readFileOrStdin(yamlPathOrStdin: string): Promise<string> {
    if (yamlPathOrStdin === '-') {
        return readStdin()
            .catch((e) => {throw new Error("stdin could not be read:", {cause: e});});
    } else {
        return fs.promises.readFile(yamlPathOrStdin, 'utf-8')
            .catch((e) => {throw new Error(`file ${yamlPathOrStdin} could not be read:`, {cause: e});});
    }
}

export async function parseYaml(yamlPathOrStdin: string) {
    const yamlContents = await readFileOrStdin(yamlPathOrStdin);
    return parse(yamlContents);
}

export async function parseUserConfig(yamlPathOrStdin: string) {
    const data = await parseYaml(yamlPathOrStdin);
    const result = OVERALL_MIGRATION_CONFIG.safeParse(data);
    if (!result.success) {
        throw result.error; // Throw Zod error directly
    }
    return result.data;
}
