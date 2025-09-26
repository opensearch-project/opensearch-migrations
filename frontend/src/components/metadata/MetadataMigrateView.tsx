"use client";

import { SessionStatusProps } from "@/components/session/types";
import { useMetadataMigrateAction } from "@/hooks/apiAction";
import {
  Alert,
  Box,
  Button,
  SpaceBetween,
  Table,
  TableProps,
  TextFilter,
} from "@cloudscape-design/components";
import { ItemResult } from "@/generated/api";
import { useCollection } from "@cloudscape-design/collection-hooks";
import { useMemo } from "react";

interface MetadataMigrateViewProps extends SessionStatusProps {
  readonly dryRun: boolean;
}

export default function MetadataMigrateView({
  sessionName,
  dryRun,
}: MetadataMigrateViewProps) {
  const {
    run: runMetadataAction,
    data,
    reset,
    isLoading,
    error,
  } = useMetadataMigrateAction(dryRun);

  const triggerMetadataAction = async () => {
    reset();
    await runMetadataAction(sessionName);
  };

  type ItemKind = "Index" | "Alias" | "Index Template" | "Component Template";
  type ItemResultWithType = ItemResult & { type: ItemKind };

  const metadataItems = useMemo(() => {
    function withType<T extends ItemResult>(
      items: T[] | undefined,
      type: ItemKind,
    ): ItemResultWithType[] {
      return (items ?? []).map((item) => ({ ...item, type }));
    }

    return [
      ...withType(data?.items?.aliases, "Alias"),
      ...withType(data?.items?.indexes, "Index"),
      ...withType(data?.items?.indexTemplates, "Index Template"),
      ...withType(data?.items?.componentTemplates, "Component Template"),
    ];
  }, [data]);

  const itemColumnDefinitions: TableProps.ColumnDefinition<ItemResultWithType>[] =
    [
      {
        id: "name",
        header: "Index Name",
        cell: (item) => item.name,
        sortingField: "name",
      },
      {
        id: "type",
        header: "Type",
        cell: (item) => item.type,
        sortingField: "type",
      },
      {
        id: "result",
        header: "Success",
        cell: (item) => item.successful,
        sortingField: "successful",
      },
      {
        id: "error-details",
        header: "Error Type",
        cell: (item) => item.failure?.message ?? "",
        sortingField: "failure?.message",
      },
    ];

  const itemCollection = useCollection(metadataItems, {
    filtering: {
      empty: (
        <Box textAlign="center" color="inherit">
          <b>No items</b>
        </Box>
      ),
      noMatch: <Box>No transformations match the filter criteria.</Box>,
    },
    sorting: {
      defaultState: {
        sortingColumn: {
          sortingField: "name",
        },
      },
    },
  });

  type Transformation = {
    name: string;
    description: string;
  };
  const transformations = useMemo(() => {
    const list = data?.transformations?.transformers ?? [];
    return list.map(
      ({ name, description }) => ({ name, description }) as Transformation,
    );
  }, [data]);

  const transformationsColumnDefinitions: TableProps.ColumnDefinition<Transformation>[] =
    [
      {
        id: "name",
        header: "Name",
        cell: (item) => item.name,
        sortingField: "name",
      },
      {
        id: "description",
        header: "Description",
        cell: (item) => item.description,
        sortingField: "description",
      },
    ];

  const transformationsCollection = useCollection(transformations, {
    filtering: {
      empty: (
        <Box textAlign="center" color="inherit">
          <b>No items</b>
        </Box>
      ),
      noMatch: <Box>No transformations match the filter criteria.</Box>,
    },
    sorting: {
      defaultState: {
        sortingColumn: {
          sortingField: "name",
        },
      },
    },
  });

  const maxHeight = "300px";
  return (
    <SpaceBetween size="l">
      {error && (
        <Alert type="error" header="Error">
          {String(error)}
        </Alert>
      )}

      <Button
        onClick={triggerMetadataAction}
        loading={isLoading}
        disabled={isLoading}
      >
        Run Metadata {dryRun ? "Evaluation" : "Migration"}
      </Button>

      <div style={{ maxHeight, overflow: "auto" }}>
        <Table<ItemResultWithType>
          {...itemCollection.collectionProps}
          columnDefinitions={itemColumnDefinitions}
          items={itemCollection.items}
          empty={
            <Box textAlign="center" color="inherit">
              <b>No items</b>
            </Box>
          }
          loading={isLoading}
          filter={
            <TextFilter
              {...itemCollection.filterProps}
              filteringPlaceholder="Find an item"
            />
          }
          variant="borderless"
          stickyHeader
        />
      </div>
      <div style={{ maxHeight, overflow: "auto" }}>
        <Table<Transformation>
          {...transformationsCollection.collectionProps}
          columnDefinitions={transformationsColumnDefinitions}
          items={transformationsCollection.items}
          empty={
            <Box textAlign="center" color="inherit">
              <b>No items</b>
            </Box>
          }
          loading={isLoading}
          filter={
            <TextFilter
              {...transformationsCollection.filterProps}
              filteringPlaceholder="Find a transformation"
            />
          }
          variant="borderless"
          stickyHeader
        />
      </div>
    </SpaceBetween>
  );
}
