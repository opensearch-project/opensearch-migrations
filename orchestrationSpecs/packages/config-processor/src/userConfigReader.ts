import fs from "node:fs";
import {parse} from "yaml";
import {OVERALL_MIGRATION_CONFIG} from "@opensearch-migrations/schemas";

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

export async function parseUserConfig(yamlPathOrStdin: string): Promise<any> {
    return readFileOrStdin(yamlPathOrStdin)
        .then(yamlContents=>parse(yamlContents))
        .catch(e => {throw new Error("yaml parse error:", e);})
        .then(data=> OVERALL_MIGRATION_CONFIG.safeParse(data))
        .catch(e=>{throw new Error("contents do not match the schema:", {cause: e});})
}