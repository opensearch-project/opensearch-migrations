/**
 * ConfigurationBuilder Component
 * 
 * Main component that integrates the schema-driven form with the
 * editable code panel for bidirectional configuration editing.
 * 
 * Accepts a JSON Schema as a prop for form generation and validation.
 */

import React, { useRef } from 'react';
import { Grid, Box, SpaceBetween, Spinner, Alert } from '@cloudscape-design/components';
import { SchemaForm } from './schema-form';
import { EditableCodePanel } from './code-panel';
import { useJsonSchemaForm } from '../hooks';
import type { JSONSchema7 } from '../types';

/**
 * Props for ConfigurationBuilder component
 */
export interface ConfigurationBuilderProps {
  /** The JSON Schema to use for form generation */
  jsonSchema: JSONSchema7 | null;
  /** Whether the schema is currently being loaded */
  isLoading?: boolean;
  /** Error message if schema loading failed */
  loadError?: string | null;
}

/**
 * Main configuration builder with bidirectional editing
 */
export function ConfigurationBuilder({
  jsonSchema,
  isLoading = false,
  loadError = null,
}: ConfigurationBuilderProps): React.ReactElement {
  // Debug logging
  console.log('[ConfigurationBuilder] render:', { 
    hasSchema: !!jsonSchema, 
    schemaKeys: jsonSchema ? Object.keys(jsonSchema) : [],
    schemaType: jsonSchema?.type,
    isLoading, 
    loadError 
  });

  // Ref for the form container (for scrolling to focused fields)
  const formContainerRef = useRef<HTMLDivElement>(null);

  // Use the JSON Schema-driven form hook
  // Only initialize when we have a valid schema
  const {
    values,
    errorsByPath,
    isValidating,
    formConfig,
    content,
    format,
    annotations,
    parseError,
    setValue,
    setContent,
    setFormat,
  } = useJsonSchemaForm({
    jsonSchema: jsonSchema ?? { type: 'object' as const },
    initialValues: {},
    initialFormat: 'yaml',
    includeAdvanced: true,
    validationDelay: 300,
  });

  // Show loading state
  if (isLoading) {
    return (
      <Box padding="l" textAlign="center">
        <SpaceBetween size="m" direction="vertical" alignItems="center">
          <Spinner size="large" />
          <Box variant="p" color="text-body-secondary">
            Loading schema...
          </Box>
        </SpaceBetween>
      </Box>
    );
  }

  // Show error state
  if (loadError) {
    return (
      <Box padding="l">
        <Alert type="error" header="Failed to load schema">
          {loadError}
        </Alert>
      </Box>
    );
  }

  // Show message when no schema is available
  if (!jsonSchema) {
    return (
      <Box padding="l">
        <Alert type="info" header="No schema loaded">
          Please select a schema source to begin configuring your migration.
        </Alert>
      </Box>
    );
  }


  return (
    <Box padding="l">
      <SpaceBetween size="l">
        {/* Main Content Grid */}
        <Grid
          gridDefinition={[
            { colspan: { default: 12, m: 6 } },
            { colspan: { default: 12, m: 6 } },
          ]}
        >
          {/* Form Panel */}
          <div className="form-panel-container" ref={formContainerRef}>
            <SchemaForm
              formConfig={formConfig}
              values={values}
              errorsByPath={errorsByPath}
              onChange={setValue}
              disabled={false}
              loading={isValidating}
            />
          </div>

          {/* Code Panel */}
          <div className="code-panel-container">
            <EditableCodePanel
              content={content}
              format={format}
              onContentChange={setContent}
              onFormatChange={setFormat}
              annotations={annotations}
              parseError={parseError}
              isValidating={isValidating}
              title="Configuration Editor"
              description="Edit your configuration directly in YAML or JSON"
            />
          </div>
        </Grid>
      </SpaceBetween>
    </Box>
  );
}

export default ConfigurationBuilder;
