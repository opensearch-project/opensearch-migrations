'use client';

import { SnapshotIndex, SnapshotIndexes } from "@/generated/api/types.gen";
import Box from "@cloudscape-design/components/box";
import Header from "@cloudscape-design/components/header";
import SpaceBetween from "@cloudscape-design/components/space-between";
import StatusIndicator from "@cloudscape-design/components/status-indicator";
import Table, { TableProps } from "@cloudscape-design/components/table";
import Alert from "@cloudscape-design/components/alert";
import { useCollection } from '@cloudscape-design/collection-hooks';
import { formatBytes } from "@/utils/sizeLimits";
import { TextFilter } from "@cloudscape-design/components";

interface SnapshotIndexesViewProps {
  isLoading: boolean;
  snapshotIndexes: SnapshotIndexes | null;
  error: string | null;
}

export default function SnapshotIndexesView({
  isLoading,
  snapshotIndexes,
  error
}: SnapshotIndexesViewProps) {
  const hasData = snapshotIndexes && snapshotIndexes.indexes.length > 0;

  const columnDefinitions: TableProps.ColumnDefinition<SnapshotIndex>[]  = [
    {
      id: 'name', header: 'Index name', cell: (item) => item.name, sortingField:"name" },
    {
      id: 'documents',
      header: 'Documents',
      cell: (item) => item.document_count.toLocaleString(),
      sortingField:"document_count"
    },
    {
      id: 'size',
      header: 'Size',
      cell: (item) => formatBytes(item.size_bytes),
      sortingField:"size_bytes"
    }
  ];

  const indexes = useCollection(snapshotIndexes?.indexes ?? [], {
    filtering: {
      empty: (
        <Box>
          There are no indexes on the source cluster.
        </Box>
      ),
      noMatch: <Box>No sessions with filter criteria.</Box>
    },
    sorting: {
      defaultState: {
        sortingColumn: {
          sortingField: "name"
        }
      } 
    }
  })

  return (
    <SpaceBetween size="l">
      <Header variant="h2" description="Review indexes that will be included in the snapshot">Review items</Header>

      {isLoading && (
        <Box padding="l">
          <StatusIndicator type="loading">Loading indexes...</StatusIndicator>
        </Box>
      )}

      {error && (
        <Alert type="error" header="Error loading indexes">
          {String(error)}
        </Alert>
      )}

      {!isLoading && !error && (
        <>
          {hasData ? (
            <div style={{height: '300px', overflow: 'auto'}}>
            <Table
              {...indexes.collectionProps}
              columnDefinitions={columnDefinitions}
              items={indexes.items}
              empty={
                <Box textAlign="center" color="inherit">
                  <b>No indexes</b>
                  <Box padding={{ bottom: "s" }} variant="p" color="inherit">
                    No indexes were found in this snapshot.
                  </Box>
                </Box>
              }
              filter={
                <TextFilter
                  {...indexes.filterProps}
                  filteringPlaceholder="Find an index"
                />
              }
              variant="borderless"
              stickyHeader
            />
            </div>
          ) : (
            <Alert type="info" header="No indexes available">
              No indexes found in the snapshot.
            </Alert>
          )}
      </>
      )}
    </SpaceBetween>
  );
}
