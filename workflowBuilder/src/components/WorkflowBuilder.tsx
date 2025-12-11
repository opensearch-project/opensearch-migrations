/**
 * WorkflowBuilder Component
 * 
 * A visual workflow builder that uses JSON Schema for form generation.
 * This component demonstrates the schema-driven approach with bidirectional
 * sync between form fields and YAML/JSON code editor.
 */

import React, { useCallback, useRef } from 'react';
import {
  Container,
  Header,
  SpaceBetween,
  Grid,
  Button,
  Box,
  StatusIndicator,
} from '@cloudscape-design/components';
import { SchemaForm } from './schema-form';
import { EditableCodePanel } from './code-panel';
import { useJsonSchemaForm } from '../hooks';
import type { JSONSchema7 } from '../types';

/**
 * Props for WorkflowBuilder
 */
export interface WorkflowBuilderProps {
  /** JSON Schema for the workflow configuration */
  schema: JSONSchema7;
  /** Initial values for the form */
  initialValues?: Record<string, unknown>;
  /** Whether to include advanced fields in the form */
  includeAdvanced?: boolean;
  /** Callback when values change */
  onChange?: (values: Record<string, unknown>) => void;
  /** Callback when form is submitted */
  onSubmit?: (values: Record<string, unknown>) => void;
  /** Title for the builder */
  title?: string;
  /** Description for the builder */
  description?: string;
  /** Whether to show the code panel */
  showCodePanel?: boolean;
  /** Test ID for testing */
  testId?: string;
}

/**
 * WorkflowBuilder - Schema-driven form builder with code editor
 */
export function WorkflowBuilder({
  schema,
  initialValues = {},
  includeAdvanced = true,
  onChange,
  onSubmit,
  title = 'Workflow Configuration',
  description,
  showCodePanel = true,
  testId = 'workflow-builder',
}: WorkflowBuilderProps): React.ReactElement {
  // Ref for the form container (for scrolling to focused fields)
  const formContainerRef = useRef<HTMLDivElement>(null);

  // Use the JSON Schema form hook
  const {
    values,
    errors,
    errorsByPath,
    isValid,
    isDirty,
    formConfig,
    content,
    format,
    annotations,
    parseError,
    setValue,
    setContent,
    setFormat,
    validate,
    reset,
  } = useJsonSchemaForm({
    jsonSchema: schema,
    initialValues: initialValues as Record<string, unknown>,
    includeAdvanced,
    onChange,
  });

  // Handle form submission
  const handleSubmit = useCallback(() => {
    if (validate()) {
      onSubmit?.(values);
    }
  }, [validate, values, onSubmit]);

  // Handle reset
  const handleReset = useCallback(() => {
    reset();
  }, [reset]);

  // Convert OpenAPIFormConfig to FormConfig for SchemaForm
  const formConfigForSchemaForm = {
    ...formConfig,
    schema: undefined, // SchemaForm doesn't need the original schema
  };

  return (
    <div data-testid={testId}>
      <SpaceBetween size="l">
        {/* Header */}
        <Header
          variant="h1"
          description={description}
          actions={
            <SpaceBetween direction="horizontal" size="xs">
              <Button
                onClick={handleReset}
                disabled={!isDirty}
                data-testid={`${testId}-reset-button`}
              >
                Reset
              </Button>
              <Button
                variant="primary"
                onClick={handleSubmit}
                disabled={!isValid}
                data-testid={`${testId}-submit-button`}
              >
                Save
              </Button>
            </SpaceBetween>
          }
        >
          {title}
        </Header>

        {/* Status indicator */}
        <Box>
          <StatusIndicator
            type={isValid ? 'success' : 'error'}
            data-testid={`${testId}-status`}
          >
            {isValid ? 'Configuration is valid' : `${errors.length} validation error(s)`}
          </StatusIndicator>
        </Box>

        {/* Main content */}
        <Grid
          gridDefinition={
            showCodePanel
              ? [{ colspan: 6 }, { colspan: 6 }]
              : [{ colspan: 12 }]
          }
        >
          {/* Form Panel */}
          <Container
            header={<Header variant="h2">Form</Header>}
            data-testid={`${testId}-form-panel`}
          >
            <div ref={formContainerRef}>
              <SchemaForm
                formConfig={formConfigForSchemaForm}
                values={values}
                errorsByPath={errorsByPath}
                onChange={setValue}
              />
            </div>
          </Container>

          {/* Code Panel */}
          {showCodePanel && (
            <div data-testid={`${testId}-code-panel`}>
              <EditableCodePanel
                content={content}
                format={format}
                annotations={annotations}
                parseError={parseError}
                onContentChange={setContent}
                onFormatChange={setFormat}
              />
            </div>
          )}
        </Grid>
      </SpaceBetween>
    </div>
  );
}

export default WorkflowBuilder;
