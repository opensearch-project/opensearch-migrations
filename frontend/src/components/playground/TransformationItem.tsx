"use client";

import { useState } from "react";
import BoardItem from "@cloudscape-design/board-components/board-item";
import Header from "@cloudscape-design/components/header";
import Button from "@cloudscape-design/components/button";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Input from "@cloudscape-design/components/input";
import { BoardProps } from "@cloudscape-design/board-components/board";
import { Transformation } from "@/context/PlaygroundContext";
import { boardItemI18nStrings } from "./boardItemI18nStrings";
import AceEditorComponent from "./AceEditorComponent";
import { usePlaygroundActions } from "@/hooks/usePlaygroundActions";

interface TransformationItemProps {
  item: BoardProps.Item<Transformation>;
  onRemove: (id: string) => void;
}

export default function TransformationItem({
  item,
  onRemove,
}: TransformationItemProps) {
  const [isEditing, setIsEditing] = useState(false);
  const [editName, setEditName] = useState(item.data.name);
  const { updateTransformation } = usePlaygroundActions();

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
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();

                  if (isEditing) {
                    // When in edit mode, save the changes
                    if (editName.trim()) {
                      updateTransformation(
                        item.id,
                        editName,
                        item.data.content,
                      );
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
          {isEditing ? (
            <Input
              value={editName}
              onChange={({ detail }) => setEditName(detail.value)}
              onKeyDown={({ detail }) => {
                if (detail.key === "Enter") {
                  // Save the transformation
                  if (editName.trim()) {
                    updateTransformation(item.id, editName, item.data.content);
                  } else {
                    setEditName(item.data.name); // Reset to original if empty
                  }
                  setIsEditing(false);
                } else if (detail.key === "Escape") {
                  setEditName(item.data.name); // Reset to original
                  setIsEditing(false);
                }
              }}
              // Remove onBlur handler completely to prevent any blur-related issues
              autoFocus
            />
          ) : (
            item.data.name
          )}
        </Header>
      }
      i18nStrings={boardItemI18nStrings}
    >
      <AceEditorComponent itemId={item.id} mode="javascript" />
    </BoardItem>
  );
}
