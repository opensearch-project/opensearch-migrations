/**
 * Zod GlobalMeta Type Extension
 * 
 * This module extends Zod's GlobalMeta interface to define UI metadata
 * that flows through to OpenAPI/JSON Schema via @asteasolutions/zod-to-openapi.
 * 
 * Usage:
 * ```typescript
 * import 'zod';
 * import './zodMeta'; // Side-effect import to extend GlobalMeta
 * 
 * const schema = z.string().meta({
 *   title: "Field Title",
 *   placeholder: "Enter value...",
 *   group: "my-group",
 *   order: 1
 * });
 * ```
 */

import 'zod';

declare module 'zod' {
    interface GlobalMeta {
        // ============================================
        // UI Presentation
        // ============================================
        
        /** Display title for the field (overrides auto-generated label) */
        title?: string;
        
        /** Placeholder text for input fields */
        placeholder?: string;
        
        /** Constraint text shown below the field (e.g., "Must be at least 8 characters") */
        constraintText?: string;
        
        /** Help text shown in a tooltip or info icon */
        helpText?: string;
        
        // ============================================
        // Field Configuration
        // ============================================
        
        /** 
         * Explicit field type override.
         * If not specified, the type is inferred from the Zod schema.
         */
        fieldType?: 
            | 'text' 
            | 'number' 
            | 'select' 
            | 'multiselect' 
            | 'checkbox' 
            | 'toggle' 
            | 'textarea' 
            | 'password' 
            | 'url' 
            | 'email'
            | 'radio'
            | 'tiles'
            | 'slider'
            | 'tags'      // For string arrays
            | 'record'    // For z.record() fields
            | 'array'     // For z.array() fields
            | 'object'    // For nested objects
            | 'union';    // For z.union() fields
        
        /** 
         * Options for select/multiselect/radio/tiles fields.
         * If not provided, options are inferred from z.enum() or z.union() of literals.
         */
        options?: Array<{
            label: string;
            value: string;
            description?: string;
            disabled?: boolean;
            iconName?: string;
            tags?: string[];
        }>;
        
        /** Whether the field is disabled */
        disabled?: boolean;
        
        /** Whether the field is read-only */
        readOnly?: boolean;
        
        // ============================================
        // Layout & Visibility
        // ============================================
        
        /** 
         * Sort order within a group.
         * Lower numbers appear first. Default is 999.
         */
        order?: number;
        
        /** 
         * Group ID for organizing fields into sections.
         * Fields with the same group are rendered together.
         */
        group?: string;
        
        /** Whether the field is hidden from the UI */
        hidden?: boolean;
        
        /** 
         * Whether the field is considered "advanced".
         * Advanced fields may be hidden by default or shown in a separate section.
         */
        advanced?: boolean;
        
        /** 
         * Column span for grid layouts.
         * 1 = half width, 2 = full width.
         */
        colSpan?: 1 | 2;
        
        // ============================================
        // Validation Display
        // ============================================
        
        /** 
         * Custom error messages keyed by Zod error code.
         * Overrides default Zod error messages.
         */
        errorMessages?: Record<string, string>;
        
        // ============================================
        // Conditional Rendering
        // ============================================
        
        /** 
         * Condition for showing/hiding the field based on other field values.
         */
        showWhen?: {
            /** Path to the field to check */
            field: string;
            /** Value to compare against */
            value: unknown;
            /** Comparison operator (default: 'eq') */
            operator?: 'eq' | 'neq' | 'in' | 'notIn';
        };
        
        // ============================================
        // Record/Array Specific
        // ============================================
        
        /** Title for individual items in a record or array */
        itemTitle?: string;
        
        /** Text for the "Add" button in records/arrays */
        addButtonText?: string;
        
        /** Minimum number of items (for arrays) */
        minItems?: number;
        
        /** Maximum number of items (for arrays) */
        maxItems?: number;
        
        // ============================================
        // Union Specific
        // ============================================
        
        /** 
         * Discriminator value for this variant in a union.
         * Used to identify which variant is selected.
         */
        discriminator?: string;
        
        /** 
         * Labels for union variants, keyed by discriminator value.
         * Used at the union level to provide human-readable labels.
         */
        variantLabels?: Record<string, string>;
    }
}

// Export empty object to make this a module
export {};
