import {Readable} from 'stream';
import {z, ZodError} from 'zod';
import {parse} from "yaml";

export class InputValidationElement {
    constructor(
        public readonly path: PropertyKey[],
        public readonly message: string
    ) {}
}

export class InputValidationError extends Error {
    constructor(
        public readonly errors: InputValidationElement[]
    ) {
        super();
        this.name = 'InputValidationError';
        Error.captureStackTrace?.(this, this.constructor);
    }

    get message(): string {
        return `Found ${this.errors.length} errors: ${formatInputValidationError(this, {singleLine: true})}`;
    }
}

export function formatInputValidationError(
    e: InputValidationError,
    options?: { singleLine?: boolean }
): string {
    const singleLine = options?.singleLine ?? false;

    return e.errors
        .map(i => [i.message, i.path.map(pk => pk.toString()).join(".")])
        .map(([k, v]) =>
            singleLine
                ? `${k} at: ${v}`
                : `${k}... at:\n  ${v}`
        )
        .join(singleLine ? "; " : "\n");
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
        const result = this.inputStrictSchema.safeParse(strippedData);

        if (!result.success) {
            throw new InputValidationError(
                result.error.issues.map(errItem =>
                    new InputValidationElement(errItem.path, errItem.message)
                )
            );
        }

        return result.data;
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
