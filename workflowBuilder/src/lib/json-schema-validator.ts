/**
 * JSON Schema Validator using Ajv
 * 
 * This module provides JSON Schema validation using Ajv (Another JSON Schema Validator),
 * the industry-standard JSON Schema validator. It replaces the custom schema-to-zod converter
 * with a more robust, feature-complete solution.
 * 
 * Benefits over custom converter:
 * - Full JSON Schema Draft 7 support (including $ref, oneOf, anyOf, etc.)
 * - Better error messages with ajv-errors
 * - Comprehensive format validation (email, uri, uuid, date-time, etc.)
 * - Industry standard with extensive testing
 * - Better performance with schema compilation
 */

import Ajv, { type ErrorObject, type ValidateFunction } from 'ajv';
import addFormats from 'ajv-formats';
import type { JSONSchema7 } from '../types/json-schema.types';
import type { AjvFieldError, JsonSchemaValidationResult } from '../types/validation';

/**
 * Create and configure an Ajv instance with formats and error handling
 * 
 * Configuration:
 * - allErrors: true - Collect all errors, not just the first one
 * - verbose: true - Include schema and data in errors for better debugging
 * - strict: false - Allow unknown keywords (for OpenAPI extensions)
 * - coerceTypes: true - Coerce types when possible (e.g., "123" -> 123)
 * - useDefaults: true - Apply default values from schema
 * - removeAdditional: false - Don't remove additional properties by default
 */
export function createAjvInstance(): Ajv {
  const ajv = new Ajv({
    allErrors: true,
    verbose: true,
    strict: false,
    coerceTypes: false, // Disable type coercion for strict validation
    useDefaults: true,
    removeAdditional: false,
  });

  // Add format validators (email, uri, uuid, date-time, etc.)
  addFormats(ajv);

  return ajv;
}

/**
 * Convert Ajv validation errors to our FieldError format
 * 
 * Maps Ajv's ErrorObject format to our application's FieldError format,
 * converting JSON Pointer paths to dot notation and providing clear error messages.
 * 
 * @param errors - Array of Ajv ErrorObject
 * @returns Array of AjvFieldError in our application format
 * 
 * @example
 * ```ts
 * const ajvErrors = [
 *   { instancePath: '/user/email', message: 'must match format "email"' }
 * ];
 * const fieldErrors = ajvErrorToFieldErrors(ajvErrors);
 * // Returns: [{ path: 'user.email', message: 'must match format "email"', code: 'format', ... }]
 * ```
 */
export function ajvErrorToFieldErrors(errors: ErrorObject[]): AjvFieldError[] {
  return errors.map((error) => {
    // Convert JSON Pointer path to dot notation
    // e.g., "/user/email" -> "user.email", "" -> "" (root)
    const path = error.instancePath
      .split('/')
      .filter(Boolean)
      .join('.');

    // Build a clear error message
    let message = error.message || 'Validation failed';
    
    // For required errors, include the missing property name
    if (error.keyword === 'required' && error.params?.missingProperty) {
      const missingProp = error.params.missingProperty as string;
      const fullPath = path ? `${path}.${missingProp}` : missingProp;
      message = `Missing required field: ${fullPath}`;
    }
    
    // For type errors, be more specific
    if (error.keyword === 'type' && error.params?.type) {
      message = `Must be of type ${error.params.type}`;
    }

    // For enum errors, show allowed values
    if (error.keyword === 'enum' && error.params?.allowedValues) {
      const values = (error.params.allowedValues as unknown[]).map(v => JSON.stringify(v)).join(', ');
      message = `Must be one of: ${values}`;
    }

    return {
      path,
      message,
      code: error.keyword,
      keyword: error.keyword,
      params: error.params,
    };
  });
}

/**
 * Validate data against a JSON Schema using Ajv
 * 
 * This is the main validation function that validates data against a JSON Schema
 * and returns a structured result with success status, validated data, and any errors.
 * 
 * @param schema - JSON Schema to validate against
 * @param data - Data to validate
 * @returns Validation result with success flag, data, and errors
 * 
 * @example
 * ```ts
 * const schema = {
 *   type: 'object',
 *   required: ['name'],
 *   properties: {
 *     name: { type: 'string' },
 *     age: { type: 'number', minimum: 0 }
 *   }
 * };
 * 
 * const result = validateWithJsonSchema(schema, { name: 'John', age: 30 });
 * if (result.success) {
 *   console.log('Valid:', result.data);
 * } else {
 *   console.log('Errors:', result.errors);
 * }
 * ```
 */
export function validateWithJsonSchema<T = unknown>(
  schema: JSONSchema7,
  data: unknown
): JsonSchemaValidationResult<T> {
  const ajv = createAjvInstance();
  const validate = ajv.compile(schema);
  
  const valid = validate(data);

  if (valid) {
    return {
      success: true,
      data: data as T,
      errors: [],
    };
  }

  const errors = validate.errors ? ajvErrorToFieldErrors(validate.errors) : [];

  return {
    success: false,
    data: undefined,
    errors,
  };
}

/**
 * Create a reusable validator function for a specific schema
 * 
 * This compiles the schema once and returns a validator function that can be
 * reused multiple times for better performance.
 * 
 * @param schema - JSON Schema to create validator for
 * @returns Validator function that can be called with data
 * 
 * @example
 * ```ts
 * const schema = { type: 'object', properties: { name: { type: 'string' } } };
 * const validator = createJsonSchemaValidator(schema);
 * 
 * const result1 = validator({ name: 'John' });
 * const result2 = validator({ name: 'Jane' });
 * ```
 */
export function createJsonSchemaValidator<T = unknown>(
  schema: JSONSchema7
): (data: unknown) => JsonSchemaValidationResult<T> {
  const ajv = createAjvInstance();
  const validate = ajv.compile(schema);

  return (data: unknown): JsonSchemaValidationResult<T> => {
    const valid = validate(data);

    if (valid) {
      return {
        success: true,
        data: data as T,
        errors: [],
      };
    }

    const errors = validate.errors ? ajvErrorToFieldErrors(validate.errors) : [];

    return {
      success: false,
      data: undefined,
      errors,
    };
  };
}

/**
 * Parse and validate data, throwing an error if validation fails
 * 
 * This is a convenience function for cases where you want to throw an exception
 * on validation failure rather than handling a result object.
 * 
 * @param schema - JSON Schema to validate against
 * @param data - Data to validate
 * @returns Validated data
 * @throws Error with validation details if validation fails
 * 
 * @example
 * ```ts
 * try {
 *   const validated = parseWithJsonSchema(schema, data);
 *   console.log('Valid:', validated);
 * } catch (error) {
 *   console.error('Validation failed:', error.message);
 * }
 * ```
 */
export function parseWithJsonSchema<T = unknown>(
  schema: JSONSchema7,
  data: unknown
): T {
  const result = validateWithJsonSchema<T>(schema, data);

  if (!result.success) {
    const errorMessages = result.errors?.map(e => `${e.path}: ${e.message}`).join(', ') || 'Unknown error';
    throw new Error(`Validation failed: ${errorMessages}`);
  }

  return result.data as T;
}

/**
 * Safe parse that returns a result object instead of throwing
 * 
 * This is an alias for validateWithJsonSchema for consistency with Zod's API.
 * 
 * @param schema - JSON Schema to validate against
 * @param data - Data to validate
 * @returns Validation result with success flag, data, and errors
 */
export function safeParseWithJsonSchema<T = unknown>(
  schema: JSONSchema7,
  data: unknown
): JsonSchemaValidationResult<T> {
  return validateWithJsonSchema<T>(schema, data);
}
