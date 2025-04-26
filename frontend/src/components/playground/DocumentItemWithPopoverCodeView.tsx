import React from "react";
import Box from "@cloudscape-design/components/box";
import { Popover } from "@cloudscape-design/components";
import CodeView from "@cloudscape-design/code-view/code-view";
import javascriptHighlight from "@cloudscape-design/code-view/highlight/javascript";
import Button from "@cloudscape-design/components/button";
import SpaceBetween from "@cloudscape-design/components/space-between";
import { prettyPrintJson } from "@/utils/jsonUtils";
import { InputDocument } from "@/context/PlaygroundContext";

interface DocumentItemWithPopoverCodeViewProps {
  document: InputDocument;
  onDelete?: (id: string) => void;
  onEdit?: (document: InputDocument) => void;
}

export const DocumentItemWithPopoverCodeView: React.FC<
  DocumentItemWithPopoverCodeViewProps
> = ({ document, onDelete, onEdit }) => (
  <SpaceBetween direction="horizontal" size="xs">
    <Popover
      header={document.name}
      key={document.id}
      size="large"
      fixedWidth
      renderWithPortal
      content={
        <CodeView
          content={prettyPrintJson(document.content)}
          highlight={javascriptHighlight}
        />
      }
    >
      <Box>{document.name}</Box>
    </Popover>
    {onEdit && (
      <Button
        variant="inline-icon"
        iconName="edit"
        ariaLabel={`Edit ${document.name}`}
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          onEdit(document);
        }}
      />
    )}
    {onDelete && (
      <Button
        variant="inline-icon"
        iconName="remove"
        ariaLabel={`Delete ${document.name}`}
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          onDelete(document.id);
        }}
      />
    )}
  </SpaceBetween>
);
