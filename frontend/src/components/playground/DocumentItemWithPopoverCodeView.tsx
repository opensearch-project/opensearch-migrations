import React from "react";
import Box from "@cloudscape-design/components/box";
import { Popover } from "@cloudscape-design/components";
import CodeView from "@cloudscape-design/code-view/code-view";
import javascriptHighlight from "@cloudscape-design/code-view/highlight/javascript";
import { prettyPrintJson } from "@/utils/jsonUtils";
import { InputDocument } from "@/context/PlaygroundContext";

interface DocumentItemWithPopoverCodeViewProps {
  document: InputDocument;
}

export const DocumentItemWithPopoverCodeView: React.FC<
  DocumentItemWithPopoverCodeViewProps
> = ({ document }) => (
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
);
