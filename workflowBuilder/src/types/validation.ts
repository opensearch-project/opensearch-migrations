/**
 * Validation Types
 * 
 * Types for validation errors, results, and error mapping
 * between form fields and code editor positions.
 */

/**
 * A single field validation error
 */
export interface FieldError {
  /** Dot-notation path to the field (e.g., "source.endpoint") */
  path: string;
  /** Human-readable error message */
  message: string;
  /** Zod error code (e.g., "invalid_type", "too_small") */
  code: string;
  /** Line number in code editor (1-indexed) */
  line?: number;
  /** Column number in code editor (1-indexed) */
  column?: number;
  /** End line for multi-line errors */
  endLine?: number;
  /** End column for multi-line errors */
  endColumn?: number;
}

/**
 * Result of validating data against a schema
 */
export interface ValidationResult<T = unknown> {
  /** Whether validation passed */
  success: boolean;
  /** Validated and transformed data (if successful) */
  data?: T;
  /** Array of all validation errors */
  errors: FieldError[];
  /** Quick lookup map from path to error */
  errorsByPath: Map<string, FieldError>;
  /** Quick lookup map from line number to errors */
  errorsByLine: Map<number, FieldError[]>;
}

/**
 * Parse error from YAML/JSON parsing
 */
export interface ParseError {
  /** Error message */
  message: string;
  /** Line number where error occurred (1-indexed) */
  line?: number;
  /** Column number where error occurred (1-indexed) */
  column?: number;
  /** The problematic content snippet */
  snippet?: string;
}

/**
 * Combined result of parsing and validation
 */
export interface ParseAndValidateResult<T = unknown> {
  /** Whether both parsing and validation succeeded */
  success: boolean;
  /** Parsed and validated data (if successful) */
  data?: T;
  /** Parse error (if parsing failed) */
  parseError?: ParseError;
  /** Validation errors (if validation failed) */
  validationErrors: FieldError[];
  /** All errors combined for display */
  allErrors: FieldError[];
}

/**
 * Options for validation
 */
export interface ValidationOptions {
  /** Whether to abort on first error */
  abortEarly?: boolean;
  /** Whether to strip unknown keys */
  stripUnknown?: boolean;
  /** Custom error messages */
  messages?: Record<string, string>;
}

/**
 * Validation state for a form
 */
export interface ValidationState {
  /** Whether validation is in progress */
  isValidating: boolean;
  /** Whether the form is valid */
  isValid: boolean;
  /** Current validation errors */
  errors: FieldError[];
  /** Errors indexed by field path */
  errorsByPath: Map<string, FieldError>;
  /** Last validation timestamp */
  lastValidated?: number;
}

/**
 * Error severity levels
 */
export type ErrorSeverity = 'error' | 'warning' | 'info';

/**
 * Extended error with severity
 */
export interface ExtendedFieldError extends FieldError {
  /** Severity level of the error */
  severity: ErrorSeverity;
}

/**
 * Ajv validation error mapped to our FieldError format
 */
export interface AjvFieldError extends FieldError {
  /** Ajv error keyword (e.g., 'required', 'type', 'minLength') */
  keyword?: string;
  /** Ajv error params for additional context */
  params?: Record<string, unknown>;
}

/**
 * Validation result from Ajv
 */
export interface JsonSchemaValidationResult<T = unknown> {
  success: boolean;
  data?: T;
  errors?: AjvFieldError[];
}

// Legacy type aliases for backward compatibility
export type ValidationSeverity = ErrorSeverity;
export type ZodErrorCode = string;

/**
 * Cross-field validation rule
 */
export interface CrossFieldRule {
  /** Fields involved in the rule */
  fields: string[];
  /** Validation function */
  validate: (values: Record<string, unknown>) => boolean | string;
  /** Error message if validation fails */
  message: string;
}
