/**
 * Form state type definitions for the schema-driven form
 */
import type { z } from 'zod';
import type { FieldConfig } from './field.types';
import type { FormConfig } from './group.types';
import type { FieldError } from './validation.types';
import type { CodeFormat, EditorAnnotation } from './editor.types';

/**
 * Form state for tracking values and validation
 */
export interface FormState<T = Record<string, unknown>> {
  /** Current form values */
  values: T;
  
  /** Validation errors */
  errors: FieldError[];
  
  /** Map of path to error for quick lookup */
  errorsByPath: Map<string, FieldError>;
  
  /** Set of touched field paths */
  touchedFields: Set<string>;
  
  /** Whether the form is valid */
  isValid: boolean;
  
  /** Whether the form has been modified */
  isDirty: boolean;
  
  /** Initial values for reset */
  initialValues: T;
}

/**
 * Return type for useSchemaForm hook
 */
export interface UseSchemaFormReturn<T = Record<string, unknown>> {
  // Form state
  /** Current form values */
  values: T;
  
  /** Validation errors */
  errors: FieldError[];
  
  /** Map of path to error */
  errorsByPath: Map<string, FieldError>;
  
  /** Whether form is valid */
  isValid: boolean;
  
  /** Whether form has been modified */
  isDirty: boolean;
  
  /** Whether validation is in progress */
  isValidating: boolean;
  
  // Form config
  /** Generated form configuration */
  formConfig: FormConfig;
  
  // Editor state
  /** Current editor content */
  content: string;
  
  /** Current format (yaml/json) */
  format: CodeFormat;
  
  /** Editor annotations for errors */
  annotations: EditorAnnotation[];
  
  /** Parse error (if content is invalid) */
  parseError: string | null;
  
  // Actions
  /** Set a single field value */
  setValue: (path: string, value: unknown) => void;
  
  /** Set multiple values at once */
  setValues: (values: Partial<T>) => void;
  
  /** Set editor content (triggers parse and sync) */
  setContent: (content: string) => void;
  
  /** Change format (yaml/json) */
  setFormat: (format: CodeFormat) => void;
  
  /** Trigger validation */
  validate: () => Promise<boolean>;
  
  /** Reset form to initial values */
  reset: () => void;
  
  /** Reset to specific values */
  resetTo: (values: T) => void;
  
  // Field helpers
  /** Get value at path */
  getFieldValue: (path: string) => unknown;
  
  /** Get error for path */
  getFieldError: (path: string) => string | undefined;
  
  /** Check if field has been touched */
  isFieldTouched: (path: string) => boolean;
  
  /** Mark field as touched */
  touchField: (path: string) => void;
  
  /** Get field config by path */
  getFieldConfig: (path: string) => FieldConfig | undefined;
  
  // Advanced
  /** Get the underlying Zod schema */
  schema: z.ZodTypeAny;
  
  /** Subscribe to value changes */
  subscribe: (callback: (values: T) => void) => () => void;
}

/**
 * Props for SchemaForm component
 */
export interface SchemaFormProps<T = Record<string, unknown>> {
  /** Zod schema for the form */
  schema: z.ZodType<T>;
  
  /** Initial values */
  initialValues?: Partial<T>;
  
  /** Callback when values change */
  onChange?: (values: T) => void;
  
  /** Callback when form is submitted */
  onSubmit?: (values: T) => void;
  
  /** Callback when validation errors occur */
  onError?: (errors: FieldError[]) => void;
  
  /** Whether to show advanced fields */
  showAdvanced?: boolean;
  
  /** Whether form is disabled */
  disabled?: boolean;
  
  /** Whether form is read-only */
  readOnly?: boolean;
  
  /** Custom class name */
  className?: string;
}

/**
 * Props for FieldRenderer component
 */
export interface FieldRendererProps {
  /** Field configuration */
  field: FieldConfig;
  
  /** Current value */
  value: unknown;
  
  /** Change handler */
  onChange: (value: unknown) => void;
  
  /** Blur handler */
  onBlur?: (() => void) | undefined;
  
  /** Error message */
  error?: string | undefined;
  
  /** Whether field is disabled */
  disabled?: boolean | undefined;
  
  /** Whether field is read-only */
  readOnly?: boolean | undefined;
  
  /** Whether to show the label */
  showLabel?: boolean | undefined;
  
  /** All form values (for conditional rendering) */
  formValues?: Record<string, unknown> | undefined;
}

/**
 * Props for GroupRenderer component
 */
export interface GroupRendererProps {
  /** Group configuration */
  group: import('./group.types').GroupConfig;
  
  /** Current form values */
  values: Record<string, unknown>;
  
  /** Change handler */
  onChange: (path: string, value: unknown) => void;
  
  /** Blur handler */
  onBlur?: ((path: string) => void) | undefined;
  
  /** Errors by path */
  errorsByPath: Map<string, FieldError>;
  
  /** Whether group is disabled */
  disabled?: boolean | undefined;
  
  /** Whether group is read-only */
  readOnly?: boolean | undefined;
}

/**
 * Context value for form state
 */
export interface FormContextValue<T = Record<string, unknown>> {
  /** Current values */
  values: T;
  
  /** Set a value */
  setValue: (path: string, value: unknown) => void;
  
  /** Get a value */
  getValue: (path: string) => unknown;
  
  /** Errors by path */
  errorsByPath: Map<string, FieldError>;
  
  /** Get error for path */
  getError: (path: string) => string | undefined;
  
  /** Touched fields */
  touchedFields: Set<string>;
  
  /** Touch a field */
  touchField: (path: string) => void;
  
  /** Form config */
  formConfig: FormConfig;
  
  /** Whether form is disabled */
  disabled: boolean;
  
  /** Whether form is read-only */
  readOnly: boolean;
}

/**
 * Form action types for reducer
 */
export type FormAction<T = Record<string, unknown>> =
  | { type: 'SET_VALUE'; path: string; value: unknown }
  | { type: 'SET_VALUES'; values: Partial<T> }
  | { type: 'SET_ERRORS'; errors: FieldError[] }
  | { type: 'TOUCH_FIELD'; path: string }
  | { type: 'SET_DIRTY'; isDirty: boolean }
  | { type: 'RESET'; values: T }
  | { type: 'SET_VALIDATING'; isValidating: boolean };
