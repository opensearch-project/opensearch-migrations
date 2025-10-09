"use client";

import { useEffect, useState } from "react";
import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import Button from "@cloudscape-design/components/button";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import Board, { BoardProps } from "@cloudscape-design/board-components/board";
import { Transformation, usePlayground } from "@/context/PlaygroundContext";
import TransformationItem from "./TransformationItem";
import { usePlaygroundActions } from "@/hooks/usePlaygroundActions";
import { transformationBoardLayoutStrings } from "./TransformationBoardLayoutStrings";
import { useTransformationExecutor } from "@/hooks/useTransformationExecutor";
import { defaultContent } from "@/components/playground/DefaultTransformationContent";

export default function TransformationSection() {
  const { state } = usePlayground();
  const { addTransformation, removeTransformation, reorderTransformation } =
    usePlaygroundActions();
  const { runTransformations, isProcessing } = useTransformationExecutor();

  // Local state to track rowSpan values for each transformation
  const [itemDimensions, setItemDimensions] = useState<
    Record<string, { rowSpan: number }>
  >(
    // Initialize with default values from transformations
    Object.fromEntries(
      state.transformations.map((transform) => [transform.id, { rowSpan: 3 }]),
    ),
  );

  // Helper functions to reduce nesting depth
  const addNewTransformations = (
    transformations: Transformation[],
    dimensions: Record<string, { rowSpan: number }>,
  ) => {
    const newDimensions = { ...dimensions };
    for (const transform of transformations) {
      if (!newDimensions[transform.id]) {
        newDimensions[transform.id] = { rowSpan: 1 };
      }
    }
    return newDimensions;
  };

  const removeDeletedTransformations = (
    transformations: Transformation[],
    dimensions: Record<string, { rowSpan: number }>,
  ) => {
    const newDimensions = { ...dimensions };
    const ids = Object.keys(newDimensions);
    for (const id of ids) {
      if (!transformations.some((t) => t.id === id)) {
        delete newDimensions[id];
      }
    }
    return newDimensions;
  };

  // Sync itemDimensions when transformations change
  useEffect(() => {
    // When transformations change (added/removed), update our dimensions map
    setItemDimensions((prevDimensions) => {
      // Process additions and deletions using helper functions
      const withAdditions = addNewTransformations(
        state.transformations,
        prevDimensions,
      );
      return removeDeletedTransformations(state.transformations, withAdditions);
    });
  }, [state.transformations]);

  const handleAddTransformation = () => {
    addTransformation(
      `Transformation ${state.transformations.length + 1}`,
      defaultContent,
    );
  };

  const handleRemoveTransformation = (id: string) => {
    removeTransformation(id);
  };

  const handleItemsChange = (
    e: CustomEvent<BoardProps.ItemsChangeDetail<Transformation>>,
  ) => {
    const { items, resizedItem, movedItem } = e.detail;

    // Handle resizing - update local state
    if (resizedItem) {
      setItemDimensions((prev) => ({
        ...prev,
        [resizedItem.id]: {
          rowSpan: resizedItem.rowSpan ?? 1,
        },
      }));
    }

    // Handle reordering
    if (movedItem) {
      // Find the new index of the moved item
      const newIndex = items.findIndex((item) => item.id === movedItem.id);

      // Find the old index of the moved item in the current state
      const oldIndex = state.transformations.findIndex(
        (transform) => transform.id === movedItem.id,
      );

      if (oldIndex !== -1 && newIndex !== -1 && oldIndex !== newIndex) {
        reorderTransformation(oldIndex, newIndex);
      }
    }
  };

  return (
    <Container header={<Header variant="h3">Transformations</Header>}>
      <SpaceBetween size="m">
        <Board
          items={state.transformations.map((transform) => ({
            id: transform.id,
            rowSpan: itemDimensions[transform.id]?.rowSpan ?? 1,
            columnSpan: 4, // Always full width
            data: transform,
          }))}
          i18nStrings={transformationBoardLayoutStrings}
          onItemsChange={handleItemsChange}
          renderItem={(item: BoardProps.Item<Transformation>) => (
            <TransformationItem
              item={item}
              onRemove={handleRemoveTransformation}
            />
          )}
          empty={
            <Box margin={{ vertical: "xs" }} textAlign="center" color="inherit">
              <SpaceBetween size="m">
                <Box variant="strong" color="inherit">
                  No items
                </Box>
              </SpaceBetween>
            </Box>
          }
        />
        <Box margin={{ vertical: "xs" }} textAlign="center" color="inherit">
          <SpaceBetween direction="horizontal" size="xs">
            <Button iconName="add-plus" onClick={handleAddTransformation}>
              Add a transformation
            </Button>
            <Button
              iconName="refresh"
              onClick={runTransformations}
              loading={isProcessing}
              disabled={
                state.transformations.length === 0 ||
                state.inputDocuments.length === 0
              }
              ariaLabel="Run transformations"
            >
              Run transformations
            </Button>
          </SpaceBetween>
        </Box>
      </SpaceBetween>
    </Container>
  );
}
