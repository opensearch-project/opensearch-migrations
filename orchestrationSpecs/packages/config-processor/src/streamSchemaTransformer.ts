import {Readable} from 'stream';
import {z} from 'zod';
import {parse} from "yaml";

export function deepStrict<T extends z.ZodTypeAny>(schema: T): T {
    if (schema instanceof z.ZodObject) {
        const shape = schema.shape;
        const strictShape: any = {};

        for (const key in shape) {
            strictShape[key] = deepStrict(shape[key]);
        }

        return z.object(strictShape).strict() as unknown as T;
    }

    if (schema instanceof z.ZodArray) {
        const elementSchema = schema.element as z.ZodTypeAny;
        return z.array(deepStrict(elementSchema)) as unknown as T;
    }

    if (schema instanceof z.ZodRecord) {
        return z.record(
            schema._def.keyType,  // Don't deepStrict the key
            deepStrict(schema._def.valueType as z.ZodTypeAny)
        ) as unknown as T;
    }

    if (schema instanceof z.ZodOptional) {
        return deepStrict(schema.unwrap() as z.ZodTypeAny).optional() as unknown as T;
    }

    if (schema instanceof z.ZodNullable) {
        return deepStrict(schema.unwrap() as z.ZodTypeAny).nullable() as unknown as T;
    }

    return schema;
}

export function stripComments<T>(obj: T): T {
    if (obj === null || typeof obj !== 'object') {
        return obj;
    }

    if (Array.isArray(obj)) {
        return obj.map(stripComments) as T;
    }

    const result: any = {};
    for (const [key, value] of Object.entries(obj)) {
        // Skip keys starting with "//" or "#"
        if (key.startsWith('//') || key.startsWith('#')) {
            continue;
        }
        result[key] = stripComments(value);
    }

    return result;
}

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
        const strippedData = stripComments(data);
        return this.inputStrictSchema.parse(strippedData);
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
