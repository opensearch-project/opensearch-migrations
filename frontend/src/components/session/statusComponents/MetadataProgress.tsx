"use client";

import { StatusIndicator } from "@cloudscape-design/components";
import { TextDisplay } from "@/components/session/statusComponents";
import { MetadataResponse } from "@/generated/api/types.gen";
import { MetadataData } from "../types";

interface ItemsSummary {
  total: number;
  successful: number;
  failed: number;
}

/**
 * Gets a summary count of all metadata items
 */
function getItemsSummary(items: MetadataResponse["items"]): ItemsSummary {
  if (!items) return { total: 0, successful: 0, failed: 0 };

  let totalItems = 0;
  let successfulItems = 0;
  let failedItems = 0;

  // Process array properties that might contain items with 'successful' property
  const relevantKeys = [
    "indexTemplates",
    "componentTemplates",
    "indexes",
    "aliases",
  ];

  relevantKeys.forEach((key) => {
    const itemList = items[key as keyof typeof items];
    if (Array.isArray(itemList)) {
      totalItems += itemList.length;
      itemList.forEach((item: { successful?: boolean }) => {
        if (item.successful) {
          successfulItems++;
        } else if (item.successful === false) {
          failedItems++;
        }
      });
    }
  });

  return {
    total: totalItems,
    successful: successfulItems,
    failed: failedItems,
  };
}

/**
 * Displays a summary of metadata migration progress
 */
export function MetadataProgress({
  metadata,
}: {
  metadata?: MetadataResponse | null;
}) {
  if (!metadata || !metadata.items) {
    return <TextDisplay text="No items processed" />;
  }

  const { total, successful, failed } = getItemsSummary(metadata.items);

  if (total === 0) {
    return <TextDisplay text="No items processed" />;
  }

  return (
    <>
      {`${total} items: ${successful} successful, ${failed} failed`}
      {" "}
      {metadata.errorCount && metadata.errorCount > 0 && (
        <div style={{ marginTop: "8px" }}>
          <StatusIndicator type="error">
            {metadata.errorCount} error{metadata.errorCount > 1 ? "s" : ""} encountered
          </StatusIndicator>
        </div>
      )}
    </>
  );
}

/**
 * Displays cluster version information
 */
export function ClusterVersions({
  metadata,
}: {
  metadata?: MetadataData | null;
}) {
  if (!metadata || !metadata.clusters) {
    return <TextDisplay text="-" />;
  }

  return (
    <>
      {metadata.clusters.source && (
        <div>
          Source: {metadata.clusters.source.type} (
          {metadata.clusters.source.version})
        </div>
      )}
      {metadata.clusters.target && (
        <div>
          Target: {metadata.clusters.target.type} (
          {metadata.clusters.target.version})
        </div>
      )}
    </>
  );
}
