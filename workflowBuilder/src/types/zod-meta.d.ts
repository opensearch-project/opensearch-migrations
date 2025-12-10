/**
 * Zod 4 GlobalMeta augmentation for UI metadata
 * This extends Zod's built-in metadata system to include UI-specific properties
 */
import 'zod';

declare module 'zod' {
  interface GlobalMeta {
    // UI Presentation
    title?: string;
    placeholder?: string;
    constraintText?: string;
    helpText?: string;
    description?: string;

    // Field Configuration
    fieldType?: import('./field.types').FieldType;
    options?: import('./field.types').SelectOption[];
    disabled?: boolean;
    readOnly?: boolean;

    // Layout & Visibility
    order?: number;
    group?: string;
    hidden?: boolean;
    advanced?: boolean; // For progressive disclosure
    colSpan?: 1 | 2;

    // Validation Display
    errorMessages?: Record<string, string>;

    // Conditional Rendering
    showWhen?: {
      field: string;
      value: unknown;
      operator?: 'eq' | 'neq' | 'in' | 'notIn';
    };

    // Record/Array specific
    itemTitle?: string; // Title for items in arrays/records
    addButtonText?: string; // Custom text for add button
    minItems?: number;
    maxItems?: number;

    // Union/discriminated union specific
    discriminator?: string; // Field that determines which variant is active
    variantLabels?: Record<string, string>; // Labels for each variant
  }
}
