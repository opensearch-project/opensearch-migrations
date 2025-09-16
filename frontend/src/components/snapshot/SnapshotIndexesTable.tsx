"use client";

import {
  SnapshotIndex,
  SnapshotIndexStatus,
  SnapshotStatus,
} from "@/generated/api/types.gen";
import { formatBytes } from "@/utils/sizeLimits";
import { useCollection } from "@cloudscape-design/collection-hooks";
import { SpaceBetween } from "@cloudscape-design/components";
import Box from "@cloudscape-design/components/box";
import Table, { TableProps } from "@cloudscape-design/components/table";
import TextFilter from "@cloudscape-design/components/text-filter";
import StatusIndicator from "@cloudscape-design/components/status-indicator";

interface SnapshotIndexesTableProps {
  readonly indexes: SnapshotIndex[] | SnapshotIndexStatus[];
  readonly maxHeight?: string;
  readonly showTotalsFooter?: boolean;
  readonly emptyText?: string;
  readonly snapshotStatus?: SnapshotStatus | null;
}

export default function SnapshotIndexesTable({
  indexes,
  maxHeight = "300px",
  showTotalsFooter: showFooter = true,
  emptyText = "No indexes were found.",
  snapshotStatus = null,
}: SnapshotIndexesTableProps) {
  const getIndexStatus = (indexName: string) => {
    if (!snapshotStatus?.indexes) {
      return null;
    }
    const matchingIndex = snapshotStatus.indexes.find(
      (idx) => idx.name === indexName,
    );
    return matchingIndex ? matchingIndex.status : null;
  };
  const renderStatus = (indexName: string) => {
    const status = getIndexStatus(indexName);

    if (!status) return null;

    switch (status) {
      case "in_progress":
        return (
          <StatusIndicator type="in-progress">In progress</StatusIndicator>
        );
      case "completed":
        return <StatusIndicator type="success">Completed</StatusIndicator>;
      case "not_started":
        return <StatusIndicator type="pending">Not started</StatusIndicator>;
      default:
        return null;
    }
  };

  const columnDefinitions: TableProps.ColumnDefinition<SnapshotIndex>[] = [
    {
      id: "name",
      header: "Index name",
      cell: (item) => item.name,
      sortingField: "name",
    },
    {
      id: "documents",
      header: "Documents",
      cell: (item) => item.document_count?.toLocaleString() ?? "-",
      sortingField: "document_count",
    },
    {
      id: "size",
      header: "Size",
      cell: (item) => formatBytes(item.size_bytes) ?? "-",
      sortingField: "size_bytes",
    },
    {
      id: "shards",
      header: "Shards",
      cell: (item) => item.shard_count?.toString() ?? "0",
      sortingField: "shard_count",
    },
  ];

  if (snapshotStatus?.indexes) {
    columnDefinitions.push({
      id: "status",
      header: "Status",
      cell: (item) => renderStatus(item.name),
      sortingField: "status",
    });
  }

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
      noMatch: <Box>No indexes with filter criteria.</Box>,
    },
    sorting: {
      defaultState: {
        sortingColumn: {
          sortingField: "name",
        },
      },
    },
  });

  // Calculate totals for footer
  const totalDocuments = indexes.reduce(
    (sum, index) => sum + (index.document_count ?? 0),
    0,
  );

  const totalSize = indexes.reduce((sum, index) => sum + index.size_bytes, 0);

  return (
    <SpaceBetween size="m">
      <div style={{ maxHeight, overflow: "auto" }}>
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
          variant="borderless"
          stickyHeader
        />
      </div>
      {showFooter && (
        <Box textAlign="right">
          <b>Total:</b> {totalDocuments.toLocaleString()} documents,{" "}
          {formatBytes(totalSize)}
        </Box>
      )}
    </SpaceBetween>
  );
}
