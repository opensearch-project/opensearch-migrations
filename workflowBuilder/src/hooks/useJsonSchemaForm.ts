/**
 * useJsonSchemaForm Hook
 * 
 * Schema-driven form management hook that uses JSON Schema (OpenAPI) instead of Zod.
 * This is the new implementation that parses JSON Schema at runtime for form generation
 * and converts it to Zod for validation.
 * 
 * This hook is designed to work with pre-generated JSON Schema files from
 * @asteasolutions/zod-to-openapi, allowing the workflowBuilder to be decoupled
 * from the Zod schema definitions at runtime.
 * 
 * ## Unified Parsing and Validation Flow
 * 
 * This hook uses shared utilities from yaml-parser.ts and error-mapper.ts to ensure
 * consistent parsing and error reporting across YAML and JSON formats:
 * 
 * 1. **Parsing**: Uses `parseContent()` from yaml-parser.ts for both YAML and JSON
 * 2. **Validation**: Uses Zod schema generated from JSON Schema
 * 3. **Error Mapping**: Uses `mapErrorsToLines()` which calls `getLineForPath()` 
 *    from yaml-parser.ts for consistent line number mapping
 * 
 * This ensures validation errors appear at the correct line numbers in both formats.
 */

import { useState, useCallback, useMemo, useRef, useEffect } from 'react';
import debounce from 'lodash.debounce';
import {
    buildFormConfigFromSchema,
    validateWithJsonSchema,
    applyPartialUpdate,
    getValueByPath,
    formStateToContent,
    mapErrorsToLines,
    createEditorAnnotations,
    buildErrorMap,
    parseContent,
} from '../lib';
import type { OpenAPIFormConfig } from '../lib/openapi-parser';
import type {
    JSONSchema7,
    FieldError,
    EditorAnnotation,
    CodeFormat,
} from '../types';

/**
 * Options for useJsonSchemaForm hook
 */
export interface UseJsonSchemaFormOptions<T> {
    /** The JSON Schema for form generation and validation */
    jsonSchema: JSONSchema7;
    /** Initial form values */
    initialValues: T;
    /** Initial code format */
    initialFormat?: CodeFormat;
    /** Validation debounce delay in ms */
    validationDelay?: number;
    /** Callback when form values change */
    onChange?: (values: T) => void;
    /** Callback when validation errors change */
    onValidationChange?: (errors: FieldError[]) => void;
    /** Whether to include advanced fields */
    includeAdvanced?: boolean;
}

/**
 * Return type for useJsonSchemaForm hook
 */
export interface UseJsonSchemaFormReturn<T> {
    // Form state
    values: T;
    errors: FieldError[];
    errorsByPath: Map<string, FieldError>;
    isValid: boolean;
    isDirty: boolean;
    isValidating: boolean;

    // Form config (from JSON Schema)
    formConfig: OpenAPIFormConfig;

    // Code editor state
    content: string;
    format: CodeFormat;
    annotations: EditorAnnotation[];
    parseError: string | null;

    // Actions
    setValue: (path: string, value: unknown) => void;
    setValues: (values: Partial<T>) => void;
    setContent: (content: string) => void;
    setFormat: (format: CodeFormat) => void;
    validate: () => boolean;
    reset: () => void;
    getFieldValue: (path: string) => unknown;
    getFieldError: (path: string) => string | undefined;
    touchField: (path: string) => void;

    // Sync state
    lastChangeSource: 'form' | 'editor' | null;

    // Schema access (kept for backward compatibility, but not used internally)
    jsonSchema: JSONSchema7;
}


/**
 * Main hook for JSON Schema-driven form management
 */
export function useJsonSchemaForm<T extends Record<string, unknown>>(
    options: UseJsonSchemaFormOptions<T>
): UseJsonSchemaFormReturn<T> {
    const {
        jsonSchema,
        initialValues,
        initialFormat = 'yaml',
        validationDelay = 300,
        onChange,
        onValidationChange,
        includeAdvanced = true,
    } = options;

    // Build form config from JSON Schema
    const formConfig = useMemo(() => {
        return buildFormConfigFromSchema(jsonSchema, { includeAdvanced });
    }, [jsonSchema, includeAdvanced]);

    // Compute effective initial values: use schema defaults if initialValues is empty
    const effectiveInitialValues = useMemo(() => {
        const hasInitialValues = initialValues && Object.keys(initialValues).length > 0;
        if (hasInitialValues) {
            return initialValues;
        }
        // Use defaults from schema
        return formConfig.defaultValues as T;
    }, [initialValues, formConfig.defaultValues]);

    // Form state
    const [values, setValuesState] = useState<T>(effectiveInitialValues);
    const [errors, setErrors] = useState<FieldError[]>([]);
    const [isDirty, setIsDirty] = useState(false);
    const [isValidating, setIsValidating] = useState(false);
    const [, setTouchedFields] = useState<Set<string>>(new Set());

    // Code editor state
    const [content, setContentState] = useState<string>('');
    const [format, setFormatState] = useState<CodeFormat>(initialFormat);
    const [parseError, setParseError] = useState<string | null>(null);
    const [lastChangeSource, setLastChangeSource] = useState<'form' | 'editor' | null>(null);

    // Refs for avoiding stale closures
    const valuesRef = useRef(values);
    valuesRef.current = values;

    // Compute derived state
    const errorsByPath = useMemo(() => buildErrorMap(errors), [errors]);
    const isValid = errors.length === 0 && parseError === null;
    const annotations = useMemo(() => createEditorAnnotations(errors), [errors]);

    // Initialize content from effective initial values (schema defaults or provided values)
    useEffect(() => {
        const result = formStateToContent(effectiveInitialValues, format);
        if (result.success) {
            setContentState(result.content);
        }
    }, []); // Only on mount

    // Debounced validation using Ajv
    const debouncedValidate = useMemo(
        () =>
            debounce((data: T, currentContent: string, currentFormat: CodeFormat) => {
                setIsValidating(true);

                const result = validateWithJsonSchema(jsonSchema, data);

                if (result.success) {
                    setErrors([]);
                } else {
                    const errorsWithLines = mapErrorsToLines(result.errors || [], currentContent, currentFormat);
                    setErrors(errorsWithLines);
                }

                setIsValidating(false);
            }, validationDelay),
        [jsonSchema, validationDelay]
    );

    // Notify on validation change
    useEffect(() => {
        onValidationChange?.(errors);
    }, [errors, onValidationChange]);

    // Set a single field value
    const setValue = useCallback((path: string, value: unknown) => {
        setValuesState(prev => {
            const newValues = applyPartialUpdate(prev, path, value);

            // Update content
            const result = formStateToContent(newValues, format);
            if (result.success) {
                setContentState(result.content);
                setParseError(null);

                // Trigger validation
                debouncedValidate(newValues, result.content, format);
            }

            setIsDirty(true);
            setLastChangeSource('form');
            onChange?.(newValues);

            return newValues;
        });
    }, [format, debouncedValidate, onChange]);

    // Set multiple values at once
    const setValues = useCallback((newValues: Partial<T>) => {
        setValuesState(prev => {
            const merged = { ...prev, ...newValues } as T;

            // Update content
            const result = formStateToContent(merged, format);
            if (result.success) {
                setContentState(result.content);
                setParseError(null);

                // Trigger validation
                debouncedValidate(merged, result.content, format);
            }

            setIsDirty(true);
            setLastChangeSource('form');
            onChange?.(merged);

            return merged;
        });
    }, [format, debouncedValidate, onChange]);

    // Set content from editor
    const setContent = useCallback((newContent: string) => {
        setContentState(newContent);
        setLastChangeSource('editor');

        // Parse using shared utility from yaml-parser.ts
        // This ensures consistent parsing behavior across the application
        const parseResult = parseContent<T>(newContent, format);

        if (!parseResult.success) {
            // Handle parse error
            const error = parseResult.error!;
            setParseError(error.message);
            setErrors([{
                path: '',
                message: error.message,
                code: 'parse_error',
                line: error.line,
                column: error.column,
            }]);
            setIsDirty(true);
            return;
        }

        // Parse succeeded, now validate with Ajv
        setParseError(null);
        const validationResult = validateWithJsonSchema(jsonSchema, parseResult.data);

        if (validationResult.success) {
            // Both parse and validation succeeded
            setValuesState(validationResult.data as T);
            setErrors([]);
            onChange?.(validationResult.data as T);
        } else {
            // Parse succeeded but validation failed
            setValuesState(parseResult.data as T);
            onChange?.(parseResult.data as T);
            
            // Map validation errors to line numbers using shared utilities
            // This ensures consistent line mapping across YAML and JSON formats
            const errorsWithLines = mapErrorsToLines(validationResult.errors || [], newContent, format);
            setErrors(errorsWithLines);
        }

        setIsDirty(true);
    }, [format, jsonSchema, onChange]);

    // Change format
    const setFormat = useCallback((newFormat: CodeFormat) => {
        setFormatState(newFormat);

        // Re-serialize content in new format
        const result = formStateToContent(valuesRef.current, newFormat);
        if (result.success) {
            setContentState(result.content);

            // Re-map errors to new format
            if (errors.length > 0) {
                const errorsWithLines = mapErrorsToLines(errors, result.content, newFormat);
                setErrors(errorsWithLines);
            }
        }
    }, [errors]);

    // Manual validation using Ajv
    const validate = useCallback((): boolean => {
        const result = validateWithJsonSchema(jsonSchema, values);

        if (result.success) {
            setErrors([]);
            return true;
        }

        const errorsWithLines = mapErrorsToLines(result.errors || [], content, format);
        setErrors(errorsWithLines);

        return false;
    }, [jsonSchema, values, content, format]);

    // Reset form
    const reset = useCallback(() => {
        setValuesState(effectiveInitialValues);
        setErrors([]);
        setIsDirty(false);
        setTouchedFields(new Set());
        setParseError(null);
        setLastChangeSource(null);

        const result = formStateToContent(effectiveInitialValues, format);
        if (result.success) {
            setContentState(result.content);
        }
    }, [effectiveInitialValues, format]);

    // Get field value by path
    const getFieldValue = useCallback((path: string): unknown => {
        return getValueByPath(values, path);
    }, [values]);

    // Get field error by path
    const getFieldError = useCallback((path: string): string | undefined => {
        return errorsByPath.get(path)?.message;
    }, [errorsByPath]);

    // Mark field as touched
    const touchField = useCallback((path: string) => {
        setTouchedFields(prev => new Set(prev).add(path));
    }, []);

    return {
        // Form state
        values,
        errors,
        errorsByPath,
        isValid,
        isDirty,
        isValidating,

        // Form config
        formConfig,

        // Code editor state
        content,
        format,
        annotations,
        parseError,

        // Actions
        setValue,
        setValues,
        setContent,
        setFormat,
        validate,
        reset,
        getFieldValue,
        getFieldError,
        touchField,

        // Sync state
        lastChangeSource,

        // Schema access
        jsonSchema,
    };
}
