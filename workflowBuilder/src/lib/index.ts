/**
 * Library Exports
 * 
 * Central export point for all library utilities.
 */

// Schema Parser (legacy - Zod-based)
export {
  getSchemaMetadata,
  isSchemaOptional,
  hasSchemaDefault,
  unwrapSchema,
  getSchemaDefault,
  inferFieldType,
  extractSchemaTypeInfo,
  extractFieldConfigs,
  getAllFieldPaths,
  findFieldByPath,
  evaluateShowWhen,
  getValueByPath as getValueByPathFromSchema,
  setValueByPath,
  buildDefaultValues,
  buildFormConfig,
} from './schema-parser';
export type { BuildFormConfigOptions } from './schema-parser';

// OpenAPI Parser (new - JSON Schema-based)
export {
  inferFieldTypeFromSchema,
  extractSchemaTypeInfo as extractSchemaTypeInfoFromSchema,
  extractFieldMeta,
  extractFieldConfigsFromSchema,
  buildDefaultValuesFromSchema,
  buildFormConfigFromSchema,
  getAllFieldPathsFromSchema,
  findFieldByPathInSchema,
  resolveSchemaRef,
  buildArrayItemDefaults,
} from './openapi-parser';
export type { OpenAPIFormConfig, BuildFormConfigFromSchemaOptions } from './openapi-parser';

// JSON Schema Validator (Ajv-based)
export {
  validateWithJsonSchema,
  createJsonSchemaValidator,
  parseWithJsonSchema,
  safeParseWithJsonSchema,
  ajvErrorToFieldErrors,
  createAjvInstance,
} from './json-schema-validator';

// Error Mapper
export {
  zodErrorToFieldErrors,
  mapErrorsToLines,
  createEditorAnnotations,
  getErrorForPath,
  getErrorsForPrefix,
  buildErrorMap,
  buildErrorsByLine,
  formatFieldError,
  getFieldNameFromPath,
  hasErrors,
  countErrors,
  getFirstErrorMessage,
  mergeErrors,
} from './error-mapper';

// YAML Parser
export {
  parseYaml,
  parseJson,
  parseContent,
  serializeToYaml,
  serializeToJson,
  serializeContent,
  getPathAtPosition,
  getLineForPath,
  isValidContent,
  convertFormat,
} from './yaml-parser';
export type { ParseResult } from './yaml-parser';

// Bidirectional Sync
export {
  formStateToContent,
  contentToFormState,
  applyPartialUpdate,
  getValueByPath,
  areStatesEqual,
  mergeState,
  deepMerge,
  getChangedPaths,
  createSyncHandler,
  validateContent,
} from './bidirectional-sync';
export type { SyncToFormResult, SyncToContentResult } from './bidirectional-sync';

// Focus Utilities (for bidirectional focus sync between form and editor)
export {
  findLineForFieldPath,
  findPathAtCursorPosition,
  scrollToLine,
  scrollToElement,
  scrollToFieldWithRetry,
  expandParentSections,
  isElementVisible,
  createFocusHighlightMarker,
  calculateFieldElementId,
  findFieldElement,
  applyFieldHighlight,
  isParentPath,
  getParentPath,
  normalizePath,
  pathsEqual,
  DEFAULT_FOCUS_CONFIG,
  FOCUS_HIGHLIGHT_CLASS,
  FIELD_FOCUS_HIGHLIGHT_CLASS,
} from './focus-utils';

// Debug Logger (for development-only logging)
export {
  createDebugLogger,
  isDevMode,
  formatLogMessage,
  noopLogger,
} from './debug-logger';
export type {
  LogLevel,
  DebugLoggerConfig,
  DebugLogger,
} from './debug-logger';
