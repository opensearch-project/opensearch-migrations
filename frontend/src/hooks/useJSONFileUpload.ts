import { useState } from "react";
import {
  readFileAsText,
  validateJsonContent,
  isNewlineDelimitedJson,
} from "@/utils/jsonUtils";
import { MAX_DOCUMENT_SIZE_BYTES, formatBytes } from "@/utils/sizeLimits";

interface FileProcessingResult {
  success: boolean;
  fileName: string;
  content?: string;
  error?: string;
}

export function useJSONFileUpload() {
  const [files, setFiles] = useState<File[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const [isProcessing, setIsProcessing] = useState(false);

  const processFiles = async (): Promise<FileProcessingResult[]> => {
    if (files.length === 0) return [];

    setIsProcessing(true);
    const results: FileProcessingResult[] = [];
    const newErrors: string[] = [];

    try {
      for (const file of files) {
        const fileResult = await processSingleFile(file);
        results.push(...fileResult.results);
        newErrors.push(...fileResult.errors);
      }
    } finally {
      setErrors(newErrors);
      setIsProcessing(false);
    }

    return results;
  };

  const processSingleFile = async (
    file: File,
  ): Promise<{ results: FileProcessingResult[]; errors: string[] }> => {
    const results: FileProcessingResult[] = [];
    const errors: string[] = [];

    try {
      // Check file size before processing
      if (file.size > MAX_DOCUMENT_SIZE_BYTES) {
        const errorMsg = `File ${file.name} exceeds the maximum size limit of ${formatBytes(MAX_DOCUMENT_SIZE_BYTES)}`;
        errors.push(errorMsg);
        results.push({
          success: false,
          fileName: file.name,
          error: errorMsg,
        });
        return { results, errors };
      }

      const content = await readFileAsText(file);
      const validationError = validateJsonContent(content);

      if (validationError) {
        const errorMsg = `Error in ${file.name}: ${validationError}`;
        errors.push(errorMsg);
        results.push({
          success: false,
          fileName: file.name,
          error: validationError,
        });
      } else if (isNewlineDelimitedJson(content)) {
        results.push(...processNewlineDelimitedJson(file, content));
      } else {
        results.push({ success: true, fileName: file.name, content });
      }
    } catch (error) {
      const errorMsg = `Failed to process ${file.name}: ${error instanceof Error ? error.message : "Unknown error"}`;
      errors.push(errorMsg);
      results.push({
        success: false,
        fileName: file.name,
        error: errorMsg,
      });
    }

    return { results, errors };
  };

  const processNewlineDelimitedJson = (
    file: File,
    content: string,
  ): FileProcessingResult[] => {
    const results: FileProcessingResult[] = [];
    const lines = content.trim().split("\n");
    let lineNumber = 0;

    for (const line of lines) {
      if (line.trim()) {
        const fileName = `${file.name} [${lineNumber}]`;
        results.push({
          success: true,
          fileName,
          content: line,
        });
        lineNumber++;
      }
    }

    return results;
  };

  const clearSuccessfulFiles = (results: FileProcessingResult[]) => {
    const successfulFileNames = new Set(
      results
        .filter((result) => result.success)
        .map((result) => result.fileName.split(" [")[0]),
    ); // Remove line number if present

    setFiles((prevFiles) =>
      prevFiles.filter((file) => !successfulFileNames.has(file.name)),
    );
  };

  return {
    files,
    setFiles,
    errors,
    setErrors,
    isProcessing,
    processFiles,
    clearSuccessfulFiles,
  };
}
