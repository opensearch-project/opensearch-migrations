/**
 * Hooks Index
 * 
 * Exports all custom hooks for the workflow builder.
 */

// Legacy Zod-based form hook
export { useSchemaForm } from './useSchemaForm';
export type { UseSchemaFormOptions, UseSchemaFormReturn } from './useSchemaForm';

// New JSON Schema-based form hook
export { useJsonSchemaForm } from './useJsonSchemaForm';
export type { UseJsonSchemaFormOptions, UseJsonSchemaFormReturn } from './useJsonSchemaForm';

// Focus synchronization hook for bidirectional form/editor sync
export { useFocusSync } from './useFocusSync';
export type { UseFocusSyncOptions, UseFocusSyncReturn } from './useFocusSync';
