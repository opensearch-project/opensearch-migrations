/**
 * ConfigurationBuilder Component
 * 
 * Main component that integrates the schema-driven form with the
 * editable code panel for bidirectional configuration editing.
 * 
 * Uses the generated JSON Schema from orchestrationSpecs as the single
 * source of truth for form generation and validation.
 */

import React, { useCallback, useRef } from 'react';
import { Grid, Box, Header, SpaceBetween } from '@cloudscape-design/components';
import { SchemaForm } from './schema-form';
import { EditableCodePanel } from './code-panel';
import { useJsonSchemaForm, useFocusSync } from '../hooks';
import type { JSONSchema7 } from '../types';

// Import the generated JSON Schema
import workflowSchema from '../../generated/schemas/workflow-schema.json';

/**
 * Main configuration builder with bidirectional editing
 */
export function ConfigurationBuilder(): React.ReactElement {
  // Ref for the form container (for scrolling to focused fields)
  const formContainerRef = useRef<HTMLDivElement>(null);

  // Use the JSON Schema-driven form hook
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
    jsonSchema: workflowSchema as unknown as JSONSchema7,
    initialValues: {},
    initialFormat: 'yaml',
    includeAdvanced: true,
    validationDelay: 300,
  });

  // Use focus synchronization hook
  const {
    focusedLine,
    focusedPath,
    setFocusFromForm,
    setFocusFromEditor,
  } = useFocusSync({
    content,
    format,
    config: {
      enabled: true,
      highlightDuration: 2000,
      autoScroll: true,
      debounceDelay: 100,
    },
    formContainer: formContainerRef, // Pass the ref object, not .current
  });

  // Handle field focus from form (for focus sync)
  const handleFieldFocus = useCallback((path: string, source: 'change' | 'focus' | 'blur') => {
    if (source === 'change') {
      setFocusFromForm(path);
    }
  }, [setFocusFromForm]);

  // Handle cursor change from editor (for focus sync)
  const handleEditorCursorChange = useCallback((line: number, column: number) => {
    setFocusFromEditor(line, column);
  }, [setFocusFromEditor]);

  return (
    <Box padding="l">
      <SpaceBetween size="l">
        {/* Page Header */}
        <Header
          variant="h1"
          description="Configure your migration settings using the form or edit the YAML/JSON directly"
        >
          Migration Configuration Builder
        </Header>

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
              focusedPath={focusedPath}
              onFieldFocus={handleFieldFocus}
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
              focusedLine={focusedLine}
              onCursorChange={handleEditorCursorChange}
            />
          </div>
        </Grid>
      </SpaceBetween>
    </Box>
  );
}

export default ConfigurationBuilder;
