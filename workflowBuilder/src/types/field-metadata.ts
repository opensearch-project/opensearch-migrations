/**
 * Field metadata types for UI generation
 * 
 * Note: The Zod GlobalMeta augmentation is in zod-meta.d.ts
 * This file re-exports types for convenience and provides additional
 * type definitions used by the form generation system.
 */

// Re-export types from field.types.ts for convenience
export type { FieldType, SelectOption, FieldMeta } from './field.types';

// Re-export group types
export type { GroupMeta } from './group.types';
