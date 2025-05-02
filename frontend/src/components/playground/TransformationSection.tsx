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

export default function TransformationSection() {
  const { state } = usePlayground();
  const { addTransformation, removeTransformation, reorderTransformation } =
    usePlaygroundActions();

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
    transformations.forEach((transform) => {
      if (!newDimensions[transform.id]) {
        newDimensions[transform.id] = { rowSpan: 1 };
      }
    });
    return newDimensions;
  };

  const removeDeletedTransformations = (
    transformations: Transformation[],
    dimensions: Record<string, { rowSpan: number }>,
  ) => {
    const newDimensions = { ...dimensions };
    Object.keys(newDimensions).forEach((id) => {
      if (!transformations.some((t) => t.id === id)) {
        delete newDimensions[id];
      }
    });
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
    addTransformation(`Transformation ${state.transformations.length + 1}`, "");
  };

  const handleRemoveTransportation = (id: string) => {
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
              onRemove={handleRemoveTransportation}
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
          <Button iconName="add-plus" onClick={handleAddTransformation}>
            Add a transformation
          </Button>
        </Box>
      </SpaceBetween>
    </Container>
  );
}
