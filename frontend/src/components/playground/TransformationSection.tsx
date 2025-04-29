"use client";

import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import Button from "@cloudscape-design/components/button";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import Board, { BoardProps } from "@cloudscape-design/board-components/board";
import BoardItem from "@cloudscape-design/board-components/board-item";
import AceEditor from "react-ace";

import "ace-builds/src-noconflict/mode-json";
import "ace-builds/src-noconflict/theme-github";
import "ace-builds/src-noconflict/ext-language_tools";

import { Transformation, usePlayground } from "@/context/PlaygroundContext";
import { usePlaygroundActions } from "@/hooks/usePlaygroundActions";

const boardItemI18nStrings = {
  dragHandleAriaLabel: "Drag handle",
  dragHandleAriaDescription:
    "Use Space or Enter to activate drag, arrow keys to move, Space or Enter to submit, or Escape to discard. Be sure to temporarily disable any screen reader navigation feature that may interfere with the functionality of the arrow keys.",
  resizeHandleAriaLabel: "Resize handle",
  resizeHandleAriaDescription:
    "Use Space or Enter to activate resize, arrow keys to move, Space or Enter to submit, or Escape to discard. Be sure to temporarily disable any screen reader navigation feature that may interfere with the functionality of the arrow keys.",
};
const boardI18nStrings: BoardProps.I18nStrings<any> = (() => {
  function createAnnouncement(
    operationAnnouncement: string,
    conflicts: any,
    disturbed: any
  ) {
    const conflictsAnnouncement =
      conflicts.length > 0
        ? `Conflicts with ${conflicts.map((c) => c.data.title).join(", ")}.`
        : "";
    const disturbedAnnouncement =
      disturbed.length > 0 ? `Disturbed ${disturbed.length} items.` : "";
    return [operationAnnouncement, conflictsAnnouncement, disturbedAnnouncement]
      .filter(Boolean)
      .join(" ");
  }
  return {
    liveAnnouncementDndStarted: (operationType) =>
      operationType === "resize" ? "Resizing" : "Dragging",
    liveAnnouncementDndItemReordered: (operation) => {
      const columns = `column ${operation.placement.x + 1}`;
      const rows = `row ${operation.placement.y + 1}`;
      return createAnnouncement(
        `Item moved to ${
          operation.direction === "horizontal" ? columns : rows
        }.`,
        operation.conflicts,
        operation.disturbed
      );
    },
    liveAnnouncementDndItemResized: (operation) => {
      const columnsConstraint = operation.isMinimalColumnsReached
        ? " (minimal)"
        : "";
      const rowsConstraint = operation.isMinimalRowsReached ? " (minimal)" : "";
      const sizeAnnouncement =
        operation.direction === "horizontal"
          ? `columns ${operation.placement.width}${columnsConstraint}`
          : `rows ${operation.placement.height}${rowsConstraint}`;
      return createAnnouncement(
        `Item resized to ${sizeAnnouncement}.`,
        operation.conflicts,
        operation.disturbed
      );
    },
    liveAnnouncementDndItemInserted: (operation) => {
      const columns = `column ${operation.placement.x + 1}`;
      const rows = `row ${operation.placement.y + 1}`;
      return createAnnouncement(
        `Item inserted to ${columns}, ${rows}.`,
        operation.conflicts,
        operation.disturbed
      );
    },
    liveAnnouncementDndCommitted: (operationType) =>
      `${operationType} committed`,
    liveAnnouncementDndDiscarded: (operationType) =>
      `${operationType} discarded`,
    liveAnnouncementItemRemoved: (op) =>
      createAnnouncement(
        `Removed item ${op.item.data.title}.`,
        [],
        op.disturbed
      ),
    navigationAriaLabel: "Board navigation",
    navigationAriaDescription: "Click on non-empty item to move focus over",
    navigationItemAriaLabel: (item) => (item ? item.data.title : "Empty"),
  };
})();

export default function TransformationSection() {
  const { state } = usePlayground();
  const { addTransformation, removeTransformation } = usePlaygroundActions();

  const handleAddTransformation = () => {
    addTransformation(`Transformation ${state.transformations.length + 1}`, "");
  };

  const handleRemoveTransportation = (id: string) => {
    console.log(`Delete transformation with id: ${id}`);
    removeTransformation(id);
  };

  return (
    <Container header={<Header variant="h3">Transformations</Header>}>
      <SpaceBetween size="m">
        <Board
          items={state.transformations.map((transform) => ({
            id: transform.id,
            rowSpan: 1,
            columnSpan: 4,
            data: transform,
          }))}
          i18nStrings={boardI18nStrings}
          onItemsChange={(e) => {
            e.preventDefault();
            console.log(e);
          }}
          renderItem={(item: BoardProps.Item<Transformation>) => (
            <BoardItem
              key={item.id}
              header={
                <Header
                  actions={
                    <Button
                      variant="inline-icon"
                      iconName="remove"
                      ariaLabel={`Delete ${item.data.name}`}
                      onClick={(e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        handleRemoveTransportation(item.id);
                      }}
                    />
                  }
                >
                  {item.data.name}
                  <AceEditor
                    mode="json"
                    theme="github"
                    onChange={(e) => {
                      console.log(e);
                    }}
                    onValidate={(e) => {
                      console.log("Validate:");
                      console.log(e);
                    }}
                    name="item.id"
                    debounceChangePeriod={1000} // TODO: not convinced this is actually working
                    width="500px"
                    editorProps={{ $blockScrolling: true }}
                    setOptions={{
                      enableBasicAutocompletion: true,
                    }}
                  />
                </Header>
              }
              i18nStrings={boardItemI18nStrings}
            >
              {item.data.content}
            </BoardItem>
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
