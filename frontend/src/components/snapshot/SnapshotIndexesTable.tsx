'use client';

import { SnapshotIndex } from "@/generated/api/types.gen";
import { formatBytes } from "@/utils/sizeLimits";
import { useCollection } from '@cloudscape-design/collection-hooks';
import Box from "@cloudscape-design/components/box";
import Table, { TableProps } from "@cloudscape-design/components/table";
import TextFilter from "@cloudscape-design/components/text-filter";

interface SnapshotIndexesTableProps {
  indexes: SnapshotIndex[];
  maxHeight?: string;
  showFooter?: boolean;
  emptyText?: string;
}

export default function SnapshotIndexesTable({ 
  indexes,
  maxHeight = '300px',
  showFooter = true,
  emptyText = "No indexes were found in this snapshot."
}: SnapshotIndexesTableProps) {
  const columnDefinitions: TableProps.ColumnDefinition<SnapshotIndex>[] = [
    {
      id: 'name', 
      header: 'Index name', 
      cell: (item) => item.name, 
      sortingField: "name"
    },
    {
      id: 'documents',
      header: 'Documents',
      cell: (item) => item.document_count?.toLocaleString() ?? '0',
      sortingField: "document_count"
    },
    {
      id: 'size',
      header: 'Size',
      cell: (item) => formatBytes(item.size_bytes),
      sortingField: "size_bytes"
    },
    {
      id: 'shards',
      header: 'Shards',
      cell: (item) => item.shard_count?.toString() ?? '0',
      sortingField: "shard_count"
    }
  ];
  
  const indexCollection = useCollection(indexes, {
    filtering: {
      empty: (
        <Box textAlign="center" color="inherit">
          <b>No indexes</b>
          <Box padding={{ bottom: "s" }} variant="p" color="inherit">
            {emptyText}
          </Box>
        </Box>
      ),
      noMatch: <Box>No indexes with filter criteria.</Box>
    },
    sorting: {
      defaultState: {
        sortingColumn: {
          sortingField: "name"
        }
      } 
    }
  });

  // Calculate totals for footer
  const totalDocuments = indexes.reduce(
    (sum, index) => sum + (index.document_count ?? 0), 
    0
  );
  
  const totalSize = indexes.reduce(
    (sum, index) => sum + index.size_bytes, 
    0
  );

  return (
    <div style={{ maxHeight, overflow: 'auto' }}>
      <Table<SnapshotIndex>
        {...indexCollection.collectionProps}
        columnDefinitions={columnDefinitions}
        items={indexCollection.items}
        empty={
          <Box textAlign="center" color="inherit">
            <b>No indexes</b>
            <Box padding={{ bottom: "s" }} variant="p" color="inherit">
              {emptyText}
            </Box>
          </Box>
        }
        filter={
          <TextFilter
            {...indexCollection.filterProps}
            filteringPlaceholder="Find an index"
          />
        }
        footer={
          showFooter ? (
            <Box textAlign="right">
              <b>Total:</b> {totalDocuments.toLocaleString()} documents,{' '}
              {formatBytes(totalSize)}
            </Box>
          ) : undefined
        }
        variant="borderless"
        stickyHeader
      />
    </div>
  );
}
