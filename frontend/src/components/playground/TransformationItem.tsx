"use client";

import { useState, useRef, useCallback, useEffect } from "react";
import { SaveState, SaveStatus } from "@/types/SaveStatus";
import BoardItem from "@cloudscape-design/board-components/board-item";
import Header from "@cloudscape-design/components/header";
import Button, { ButtonProps } from "@cloudscape-design/components/button";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Input from "@cloudscape-design/components/input";
import { BoardProps } from "@cloudscape-design/board-components/board";
import { Transformation } from "@/context/PlaygroundContext";
import { transformationBoardItemStrings } from "./TransformationBoardItemStrings";
import AceEditorComponent from "./AceEditorComponent";
import { usePlaygroundActions } from "@/hooks/usePlaygroundActions";
import SaveStatusIndicator from "@/components/playground/SaveStatusIndicator";
import { useTransformationExecutor } from "@/hooks/useTransformationExecutor";

interface TransformationItemProps {
  item: BoardProps.Item<Transformation>;
  onRemove: (id: string) => void;
  onValidationChange?: (hasErrors: boolean) => void;
}

export default function TransformationItem({
  item,
  onRemove,
  onValidationChange,
}: Readonly<TransformationItemProps>) {
  const [isEditing, setIsEditing] = useState(false);
  const [editName, setEditName] = useState(item.data.name);
  const [saveStatus, setSaveStatus] = useState<SaveState>({
    status: SaveStatus.UNSAVED,
    savedAt: null,
    errors: [],
  });
  const [hasValidationErrors, setHasValidationErrors] = useState(false);
  const { updateTransformation } = usePlaygroundActions();
  const { updateValidationError } = useTransformationExecutor();
  const formatCodeRef = useRef<(() => void) | null>(null);

  const handleSaveStatusChange = useCallback((status: SaveState) => {
    setSaveStatus(status);
  }, []);

  const handleValidationChange = useCallback(
    (hasErrors: boolean) => {
      setHasValidationErrors(hasErrors);
      // Update validation error in the transformation executor
      updateValidationError(item.id, hasErrors);
      if (onValidationChange) {
        onValidationChange(hasErrors);
      }
    },
    [onValidationChange, updateValidationError, item.id]
  );

  const handleEditNameChange = (
    event: CustomEvent<ButtonProps.ClickDetail>
  ) => {
    event.preventDefault();
    event.stopPropagation();

    if (isEditing) {
      if (editName.trim()) {
        updateTransformation(item.id, editName.trim(), item.data.content);
      } else {
        setEditName(item.data.name); // Reset to original if empty
      }

      // Exit edit mode
      setIsEditing(false);
    } else {
      // Enter edit mode
      setEditName(item.data.name);
      setIsEditing(true);
    }
  };

  return (
    <BoardItem
      key={item.id}
      header={
        <Header
          actions={
            <SpaceBetween direction="horizontal" size="xs">
              <Button
                variant="inline-icon"
                iconName={isEditing ? "check" : "edit"}
                ariaLabel={
                  isEditing
                    ? `Save ${item.data.name}`
                    : `Edit ${item.data.name}`
                }
                onClick={handleEditNameChange}
              />
              <Button
                variant="inline-icon"
                iconName="script"
                ariaLabel={`Format code in ${item.data.name}`}
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  if (formatCodeRef.current) {
                    formatCodeRef.current();
                  }
                }}
              />
              <Button
                variant="inline-icon"
                iconName="remove"
                ariaLabel={`Delete ${item.data.name}`}
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  onRemove(item.id);
                }}
              />
            </SpaceBetween>
          }
        >
          <SpaceBetween direction="horizontal" size="xs">
            {isEditing ? (
              <Input
                value={editName}
                onChange={({ detail }) => setEditName(detail.value)}
                onKeyDown={({ detail }) => {
                  if (detail.key === "Enter") {
                    if (editName.trim()) {
                      updateTransformation(
                        item.id,
                        editName,
                        item.data.content
                      );
                    } else {
                      setEditName(item.data.name); // Reset to original if empty
                    }
                    setIsEditing(false);
                  } else if (detail.key === "Escape") {
                    setEditName(item.data.name); // Reset to original
                    setIsEditing(false);
                  }
                }}
                autoFocus
              />
            ) : (
              <SpaceBetween direction="horizontal" size="s">
                {item.data.name}
                <SaveStatusIndicator state={saveStatus} />
              </SpaceBetween>
            )}
          </SpaceBetween>
        </Header>
      }
      i18nStrings={transformationBoardItemStrings}
    >
      <AceEditorComponent
        itemId={item.id}
        mode="javascript"
        formatRef={formatCodeRef}
        onSaveStatusChange={handleSaveStatusChange}
        onValidationChange={handleValidationChange}
      />
    </BoardItem>
  );
}
