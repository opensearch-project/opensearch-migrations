import {Readable} from 'stream';
import {
    InputValidationError,
    parseWithValidation,
} from "@opensearch-migrations/config-edit-core";
import {z} from 'zod';
import {parse} from "yaml";

export {
    formatInputValidationError,
    InputValidationElement,
    InputValidationError,
    stripComments,
} from "@opensearch-migrations/config-edit-core";

export class StreamSchemaParser<TInput extends z.ZodSchema> {
    constructor(protected inputStrictSchema: z.ZodSchema<z.infer<TInput>>) {
    }

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
        return parseWithValidation(this.inputStrictSchema, data);
    }
}

export class StreamSchemaTransformer<
    TInput extends z.ZodSchema,
    TOutput extends z.ZodSchema
> extends StreamSchemaParser<TInput> {
    constructor(
        inputStrictSchema: z.ZodSchema<z.infer<TInput>>,
        readonly outputSchema: TOutput
    ) {
        super(inputStrictSchema);
    }

    /**
     * Transform validated input to output format
     * Override this method in subclasses for custom transformations
     */
    async transform(input: z.infer<TInput>): Promise<z.infer<TOutput>> {
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
    async processFromStream(stream: Readable): Promise<z.infer<TOutput>> {
        // 1. Convert stream to object
        const rawData = await this.streamToObject(stream);
        return this.processFromObject(rawData);
    }

    async processFromObject(rawData: unknown): Promise<z.infer<TOutput>> {
        // 2. Validate input
        const validatedInput = this.validateInput(rawData);

        // 3. Transform
        const transformed = await this.transform(validatedInput);

        // 4. Validate output
        const validatedOutput = this.validateOutput(transformed);

        return validatedOutput;
    }
}
