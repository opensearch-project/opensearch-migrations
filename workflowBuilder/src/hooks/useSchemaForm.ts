/**
 * useSchemaForm Hook
 * 
 * Main state management hook for schema-driven forms.
 * Manages form values, validation, and bidirectional sync with code editor.
 */

import { useState, useCallback, useMemo, useRef, useEffect } from 'react';
import { z } from 'zod';
import debounce from 'lodash.debounce';
import {
  buildFormConfig,
  applyPartialUpdate,
  getValueByPath,
  formStateToContent,
  contentToFormState,
  zodErrorToFieldErrors,
  mapErrorsToLines,
  createEditorAnnotations,
  buildErrorMap,
} from '../lib';
import type {
  FormConfig,
  FieldError,
  EditorAnnotation,
  CodeFormat,
  GroupMeta,
} from '../types';

/**
 * Options for useSchemaForm hook
 */
export interface UseSchemaFormOptions<T> {
  /** The Zod schema for validation */
  schema: z.ZodType<T>;
  /** Initial form values */
  initialValues: T;
  /** Initial code format */
  initialFormat?: CodeFormat;
  /** Custom group definitions */
  groups?: GroupMeta[];
  /** Validation debounce delay in ms */
  validationDelay?: number;
  /** Callback when form values change */
  onChange?: (values: T) => void;
  /** Callback when validation errors change */
  onValidationChange?: (errors: FieldError[]) => void;
}

/**
 * Return type for useSchemaForm hook
 */
export interface UseSchemaFormReturn<T> {
  // Form state
  values: T;
  errors: FieldError[];
  errorsByPath: Map<string, FieldError>;
  isValid: boolean;
  isDirty: boolean;
  isValidating: boolean;
  
  // Form config
  formConfig: FormConfig;
  
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
}

/**
 * Main hook for schema-driven form management
 */
export function useSchemaForm<T extends Record<string, unknown>>(
  options: UseSchemaFormOptions<T>
): UseSchemaFormReturn<T> {
  const {
    schema,
    initialValues,
    initialFormat = 'yaml',
    groups = [],
    validationDelay = 300,
    onChange,
    onValidationChange,
  } = options;

  // Form state
  const [values, setValuesState] = useState<T>(initialValues);
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
  
  // Build form config from schema
  const formConfig = useMemo(() => {
    return buildFormConfig(schema as z.ZodTypeAny, { groups });
  }, [schema, groups]);
  
  // Compute derived state
  const errorsByPath = useMemo(() => buildErrorMap(errors), [errors]);
  const isValid = errors.length === 0 && parseError === null;
  const annotations = useMemo(() => createEditorAnnotations(errors), [errors]);
  
  // Initialize content from initial values
  useEffect(() => {
    const result = formStateToContent(initialValues, format);
    if (result.success) {
      setContentState(result.content);
    }
  }, []); // Only on mount
  
  // Debounced validation
  const debouncedValidate = useMemo(
    () =>
      debounce((data: T, currentContent: string, currentFormat: CodeFormat) => {
        setIsValidating(true);
        
        const result = schema.safeParse(data);
        
        if (result.success) {
          setErrors([]);
        } else {
          const fieldErrors = zodErrorToFieldErrors(result.error);
          const errorsWithLines = mapErrorsToLines(fieldErrors, currentContent, currentFormat);
          setErrors(errorsWithLines);
        }
        
        setIsValidating(false);
      }, validationDelay),
    [schema, validationDelay]
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
    
    // Parse and validate
    const result = contentToFormState(newContent, format, schema);
    
    if (result.parseError) {
      setParseError(result.parseError.message);
      setErrors([{
        path: '',
        message: result.parseError.message,
        code: 'parse_error',
        line: result.parseError.line,
        column: result.parseError.column,
      }]);
    } else {
      setParseError(null);
      
      if (result.data) {
        setValuesState(result.data as T);
        onChange?.(result.data as T);
      }
      
      if (result.validationErrors.length > 0) {
        setErrors(result.validationErrors);
      } else {
        setErrors([]);
      }
    }
    
    setIsDirty(true);
  }, [format, schema, onChange]);
  
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
  
  // Manual validation
  const validate = useCallback((): boolean => {
    const result = schema.safeParse(values);
    
    if (result.success) {
      setErrors([]);
      return true;
    }
    
    const fieldErrors = zodErrorToFieldErrors(result.error);
    const errorsWithLines = mapErrorsToLines(fieldErrors, content, format);
    setErrors(errorsWithLines);
    
    return false;
  }, [schema, values, content, format]);
  
  // Reset form
  const reset = useCallback(() => {
    setValuesState(initialValues);
    setErrors([]);
    setIsDirty(false);
    setTouchedFields(new Set());
    setParseError(null);
    setLastChangeSource(null);
    
    const result = formStateToContent(initialValues, format);
    if (result.success) {
      setContentState(result.content);
    }
  }, [initialValues, format]);
  
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
  };
}
