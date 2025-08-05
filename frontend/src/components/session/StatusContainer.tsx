'use client';

import React from 'react';
import Header from '@cloudscape-design/components/header';
import Container from '@cloudscape-design/components/container';
import Alert from '@cloudscape-design/components/alert';
import ExpandableSection from '@cloudscape-design/components/expandable-section';
import Spinner from '@cloudscape-design/components/spinner';
import Box from '@cloudscape-design/components/box';
import { KeyValuePairs } from '@cloudscape-design/components';

interface StatusContainerProps {
  title: string;
  isLoading: boolean;
  error: string | null;
  children: React.ReactNode;
  loadingItems?: Array<{ label: string; placeholder?: string }>;
  columns?: number;
}

export default function StatusContainer({ 
  title, 
  isLoading, 
  error, 
  children,
  loadingItems = [
    { label: 'Status' },
    { label: 'Started' },
    { label: 'Finished' },
    { label: 'Duration' }
  ],
  columns = 2
}: StatusContainerProps) {
  const renderLoadingState = () => {
    const items = loadingItems.map(item => ({
      label: item.label,
      value: (
        <Box padding="xxs">
          {item.placeholder || <Spinner size="normal" />}
        </Box>
      )
    }));

    return (
      <KeyValuePairs columns={columns} items={items} />
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

  return (
    <Container header={<Header variant="h2">{title}</Header>}>
      {isLoading ? renderLoadingState() : error ? renderErrorState() : children}
    </Container>
  );
}
