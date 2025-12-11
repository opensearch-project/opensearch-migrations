import React from "react";
import {
  AppLayout,
  ContentLayout,
  Header,
  Link,
  Box,
  SpaceBetween,
} from "@cloudscape-design/components";
import { ConfigurationBuilder } from "./components/ConfigurationBuilder";
import { SchemaSelector } from "./components/SchemaSelector";
import { useSchemaSelection, useSchemaLoader } from "./hooks";

export const App: React.FC = () => {
  // Schema selection state with localStorage persistence
  const {
    sourceType,
    customUrl,
    resolvedUrl,
    setSourceType,
    setCustomUrl,
  } = useSchemaSelection();

  // Schema loading with caching
  const {
    schema,
    isLoading,
    error,
    reload,
  } = useSchemaLoader(resolvedUrl);

  return (
    <AppLayout
      content={
        <ContentLayout
          header={
            <Header
              variant="h1"
              description="Configure your OpenSearch migration with a visual interface. Changes are reflected in real-time in the configuration preview."
              info={
                <Link external href="https://opensearch.org/docs/latest/migration-assistant/">
                  Documentation
                </Link>
              }
            >
              Migration Configuration Builder
            </Header>
          }
        >
          <SpaceBetween size="l">
            {/* Schema Selector */}
            <Box>
              <SchemaSelector
                sourceType={sourceType}
                customUrl={customUrl}
                resolvedUrl={resolvedUrl}
                isLoading={isLoading}
                error={error}
                onSourceTypeChange={setSourceType}
                onCustomUrlChange={setCustomUrl}
                onReload={reload}
              />
            </Box>

            {/* Configuration Builder */}
            {/* Key forces re-mount when schema URL changes to reinitialize form state */}
            <ConfigurationBuilder
              key={resolvedUrl}
              jsonSchema={schema}
              isLoading={isLoading}
              loadError={error}
            />
          </SpaceBetween>
        </ContentLayout>
      }
      navigationHide
      toolsHide
      headerSelector="#header"
      footerSelector="#footer"
    />
  );
};

export default App;
