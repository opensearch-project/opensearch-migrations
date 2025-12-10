/**
 * Type definitions barrel export
 * Re-exports all types from the types directory
 */

// JSON Schema types
export type {
  JSONSchema7,
  JSONSchema7TypeName,
  OpenAPISchema,
  OpenAPIComponents,
  OpenAPIDocument,
} from './json-schema.types';

export {
  isJSONSchema7,
  isObjectSchema,
  isArraySchema,
  isUnionSchema,
  isEnumSchema,
  isConstSchema,
} from './json-schema.types';

// Field types
export type {
  FieldType,
  SelectOption,
  FieldMeta,
  SchemaTypeInfo,
  FieldConfig,
  FieldChangeEvent,
  FieldFocusEvent,
} from './field.types';

// Group types
export type {
  GroupConfig,
  GroupMeta,
  FormConfig,
  FormConfigOptions,
  PredefinedGroupId,
} from './group.types';

export { PREDEFINED_GROUPS } from './group.types';

// Validation types
export type {
  ValidationError,
  ValidationResult,
  ParseError,
  ParseAndValidateResult,
  ValidationOptions,
  ValidationState,
  CrossFieldRule,
  ErrorSeverity,
  ExtendedFieldError,
  AjvFieldError,
  JsonSchemaValidationResult,
} from './validation';

// Editor types
export type {
  CodeFormat,
  EditorAnnotation,
  EditorMarker,
  CursorPosition,
  SelectionRange,
  EditorState,
  SyncState,
  EditorConfig,
  EditorAction,
  LineMapping,
  PathLineMap,
  FocusState,
  FocusEvent,
  FocusConfig,
} from './editor.types';

// Form types
export type {
  FormState,
  UseSchemaFormReturn,
  SchemaFormProps,
  FieldRendererProps,
  GroupRendererProps,
  FormContextValue,
  FormAction,
} from './form.types';
