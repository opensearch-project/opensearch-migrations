import { Readable } from 'stream';
import { z } from 'zod';
import {parse} from "yaml";

export class StreamSchemaValidator<TInput extends z.ZodSchema, TOutput extends z.ZodSchema> {
    constructor(
        private inputSchema: TInput,
        private outputSchema: TOutput
    ) {}

    /**
     * Read stream and convert to object
     */
    async streamToObject(stream: Readable): Promise<unknown> {
        const chunks: Buffer[] = [];

        for await (const chunk of stream) {
            chunks.push(Buffer.from(chunk));
        }

        const buffer = Buffer.concat(chunks);
        const text = buffer.toString('utf-8');
        return parse(text);
    }

    /**
     * Validate input against schema
     */
    validateInput(data: unknown): z.infer<TInput> {
        return this.inputSchema.parse(data);
    }

    /**
     * Transform validated input to output format
     * Override this method in subclasses for custom transformations
     */
    transform(input: z.infer<TInput>): z.infer<TOutput> {
        throw new Error('transform() must be implemented by subclass');
    }

    /**
     * Validate output against schema
     */
    validateOutput(data: unknown): z.infer<TOutput> {
        return this.outputSchema.parse(data);
    }

    /**
     * Main processing pipeline
     */
    async process(stream: Readable): Promise<z.infer<TOutput>> {
        // 1. Convert stream to object
        const rawData = await this.streamToObject(stream);

        // 2. Validate input
        const validatedInput = this.validateInput(rawData);

        // 3. Transform
        const transformed = this.transform(validatedInput);

        // 4. Validate output
        const validatedOutput = this.validateOutput(transformed);

        return validatedOutput;
    }
}
