/**
 * Zod GlobalMeta Type Extension
 * 
 * This module extends Zod's GlobalMeta interface to define semantic metadata
 * that provides value beyond just UI presentation.
 * 
 * Usage:
 * ```typescript
 * import 'zod';
 * import './zodMeta'; // Side-effect import to extend GlobalMeta
 * 
 * const schema = z.string().meta({
 *   title: "Field Title",
 *   group: "connection-settings"
 * });
 * ```
 */

import 'zod';
import type { $input } from 'zod';

declare module 'zod' {
    interface GlobalMeta {
        // ============================================
        // Semantic Metadata (Schema-level concerns)
        // ============================================
        
        /** 
         * Human-readable title for the field.
         * Provides semantic meaning beyond the field name.
         */
        title?: string;
        
        /** 
         * Logical grouping for related fields.
         * Groups fields by functional relationship, not UI layout.
         */
        group?: string;
        
        /** 
         * Logical ordering within a group.
         * Represents conceptual importance/sequence, not UI positioning.
         */
        priority?: number;
        
        /** 
         * Whether this field represents advanced/expert configuration.
         * Semantic distinction between basic and advanced concepts.
         */
        isAdvanced?: boolean;
        
        /** 
         * Whether this field should be read-only in certain contexts.
         * Represents data constraints, not UI state.
         */
        readOnly?: boolean;
        
        // ============================================
        // Validation Enhancement
        // ============================================
        
        /** 
         * Human-readable constraint description.
         * Explains validation rules in user terms.
         */
        constraintDescription?: string;
        
        /** 
         * Custom validation error messages.
         * Provides better user experience for validation failures.
         */
        validationMessages?: Record<string, string>;
        
        // ============================================
        // Collection Metadata
        // ============================================
        
        /** 
         * Semantic label for items in arrays/records.
         * Describes what each item represents conceptually.
         */
        itemLabel?: string;
        
        /** 
         * Minimum/maximum constraints for collections.
         * Represents business rules, not UI limits.
         */
        minItems?: number;
        maxItems?: number;
        
        // ============================================
        // Union/Variant Metadata
        // ============================================
        
        /** 
         * Labels for union variants.
         * Maps discriminator values to human-readable names.
         */
        variantLabels?: Record<string, string>;
        
        // ============================================
        // Form Initialization
        // ============================================
        
        /** 
         * Example value for UI initialization.
         * Unlike `default`, this value is NOT used during schema parsing/validation.
         * Used by form builders to provide initial example values without affecting
         * the schema's validation behavior.
         * 
         * The type is automatically inferred from the schema it's attached to
         * using Zod's $input type, providing compile-time type safety.
         * This uses $input (not $output) because form initialization values
         * should match what users would provide, not the transformed output.
         * 
         * Note: This is different from `placeholder` which is used for string input hints.
         * `exampleValue` stores complete object structures for form initialization.
         */
        exampleValue?: $input;
    }
}

// Export empty object to make this a module
export {};
