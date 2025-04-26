import { useState } from "react";
import { readFileAsText, validateJsonContent } from "@/utils/jsonUtils";

interface FileProcessingResult {
  success: boolean;
  fileName: string;
  content?: string;
  error?: string;
}

export function useFileUpload() {
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
        try {
          const content = await readFileAsText(file);
          const validationError = validateJsonContent(content);
          
          if (validationError) {
            const errorMsg = `Error in ${file.name}: ${validationError}`;
            newErrors.push(errorMsg);
            results.push({ success: false, fileName: file.name, error: validationError });
          } else {
            results.push({ success: true, fileName: file.name, content });
          }
        } catch (error) {
          const errorMsg = `Failed to process ${file.name}: ${error instanceof Error ? error.message : 'Unknown error'}`;
          newErrors.push(errorMsg);
          results.push({ success: false, fileName: file.name, error: errorMsg });
        }
      }
    } finally {
      setErrors(newErrors);
      setIsProcessing(false);
    }
    
    return results;
  };

  const clearSuccessfulFiles = (results: FileProcessingResult[]) => {
    const successfulFileNames = results
      .filter(result => result.success)
      .map(result => result.fileName);
      
    setFiles(prevFiles => 
      prevFiles.filter(file => !successfulFileNames.includes(file.name))
    );
  };

  return {
    files,
    setFiles,
    errors,
    setErrors,
    isProcessing,
    processFiles,
    clearSuccessfulFiles
  };
}
