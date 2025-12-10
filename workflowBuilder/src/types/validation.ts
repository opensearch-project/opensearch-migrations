/**
 * Validation Types
 * 
 * Types for validation errors, results, and error mapping
 * between form fields and code editor positions.
 */

/**
 * Unified validation error interface
 * Consolidates FieldError, AjvFieldError, and ExtendedFieldError
 */
export interface ValidationError {
  /** Dot-notation path to the field (e.g., "source.endpoint") */
  path: string;
  /** Human-readable error message */
  message: string;
  /** Error code (e.g., "invalid_type", "too_small", "required") */
  code: string;
  /** Error severity level (defaults to 'error' if not specified) */
  severity?: 'error' | 'warning' | 'info';
  /** Line number in code editor (1-indexed) */
  line?: number;
  /** Column number in code editor (1-indexed) */
  column?: number;
  /** End line for multi-line errors */
  endLine?: number;
  /** End column for multi-line errors */
  endColumn?: number;
  /** Ajv error keyword (e.g., 'required', 'type', 'minLength') */
  keyword?: string;
  /** Ajv error params for additional context */
  params?: Record<string, unknown>;
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
  errors: ValidationError[];
  /** Quick lookup map from path to error */
  errorsByPath: Map<string, ValidationError>;
  /** Quick lookup map from line number to errors */
  errorsByLine: Map<number, ValidationError[]>;
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
  validationErrors: ValidationError[];
  /** All errors combined for display */
  allErrors: ValidationError[];
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
  errors: ValidationError[];
  /** Errors indexed by field path */
  errorsByPath: Map<string, ValidationError>;
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
export interface ExtendedFieldError extends ValidationError {
  /** Severity level of the error */
  severity: ErrorSeverity;
}

/**
 * Ajv validation error mapped to our ValidationError format
 */
export interface AjvFieldError extends ValidationError {
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
