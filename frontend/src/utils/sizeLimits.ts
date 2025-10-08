export const MAX_DOCUMENT_SIZE_MB = 5; // Maximum size for individual documents in MB
export const MAX_DOCUMENT_SIZE_BYTES = MAX_DOCUMENT_SIZE_MB * 1024 * 1024; // 5MB in bytes

export const MAX_TOTAL_STORAGE_MB = 10; // Maximum total storage size in MB
export const MAX_TOTAL_STORAGE_BYTES = MAX_TOTAL_STORAGE_MB * 1024 * 1024; // 10MB in bytes

// Helper function to format bytes to human-readable format
export const formatBytes = (bytes: number): string => {
  if (bytes === 0) return "0 Bytes";

  const k = 1024;
  const sizes = ["Bytes", "KB", "MB", "GB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));

  return (
    Number.parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i]
  );
};
