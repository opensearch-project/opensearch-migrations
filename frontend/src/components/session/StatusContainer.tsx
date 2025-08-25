'use client';

import React from 'react';
import Header from '@cloudscape-design/components/header';
import Container from '@cloudscape-design/components/container';
import Alert from '@cloudscape-design/components/alert';
import ExpandableSection from '@cloudscape-design/components/expandable-section';
import { KeyValuePairs } from '@cloudscape-design/components';
import { StatusFieldDefinition, generateLoadingItems, generateDataItems } from './statusUtils';

interface StatusContainerProps<T> {
  readonly title: string;
  readonly isLoading: boolean;
  readonly error: string | null;
  readonly data: T | null;
  readonly fields: StatusFieldDefinition[];
  readonly columns?: number;
}

export default function StatusContainer<T>({
  title,
  isLoading,
  error,
  data,
  fields,
  columns = 2,
}: StatusContainerProps<T>) {
  const renderLoadingState = () => {
    return (
      <KeyValuePairs columns={columns} items={generateLoadingItems(fields)} />
    );
  };

  const renderErrorState = () => (
    <Alert type="error" header="Failed to load data">
      <p>There was a problem loading the data. Please try again later.</p>
      <ExpandableSection header="Error details">
        <pre>{error}</pre>
      </ExpandableSection>
    </Alert>
  );

  const renderDataState = () => {
    if (data) {
      return (
        <KeyValuePairs columns={columns} items={generateDataItems(fields)} />
      );
    }
    return null;
  };

  return (
    <Container header={<Header variant="h2">{title}</Header>}>
      {isLoading
        ? renderLoadingState()
        : data
          ? renderDataState()
          : renderErrorState()}
    </Container>
  );
}
