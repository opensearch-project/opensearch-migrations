import { z } from 'zod';

// Generic validation function
export function validateData<T>(schema: z.ZodSchema<T>, data: unknown): T {
    const result = schema.safeParse(data);

    if (!result.success) {
        const errors = result.error.issues.map(err => ({
            path: err.path.join('.'),
            message: err.message,
        }));

        throw new Error(`Validation failed: ${JSON.stringify(errors, null, 2)}`);
    }

    return result.data;
}

// Async validation function
export async function validateDataAsync<T>(
    schema: z.ZodSchema<T>,
    data: unknown
): Promise<T> {
    const result = await schema.safeParseAsync(data);

    if (!result.success) {
        const errors = result.error.issues.map(err => ({
            path: err.path.join('.'),
            message: err.message,
        }));

        throw new Error(`Validation failed: ${JSON.stringify(errors, null, 2)}`);
    }

    return result.data;
}