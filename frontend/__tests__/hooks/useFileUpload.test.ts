import { renderHook, act } from "@testing-library/react";
import { useFileUpload } from "@/hooks/useFileUpload";
import { validateJsonContent, readFileAsText } from "@/utils/jsonUtils";

// Mock the readFileAsText function from jsonUtils
jest.mock("@/utils/jsonUtils", () => ({
  readFileAsText: jest.fn(),
  validateJsonContent: jest.requireActual("@/utils/jsonUtils").validateJsonContent,
  validateNewlineDelimitedJson: jest.requireActual("@/utils/jsonUtils").validateNewlineDelimitedJson,
  prettyPrintJson: jest.requireActual("@/utils/jsonUtils").prettyPrintJson,
}));

// Define types for better readability
type FileProcessingResult = {
  success: boolean;
  fileName: string;
  content?: string;
  error?: string;
};

describe("useFileUpload", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  // Helper function to setup a test with file processing
  const setupFileProcessingTest = async (
    fileContent: string,
    fileName: string,
    fileType = "application/json",
    mockImplementation?: (file: File) => Promise<string>
  ) => {
    // Setup mock
    if (mockImplementation) {
      (readFileAsText as jest.Mock).mockImplementation(mockImplementation);
    } else {
      (readFileAsText as jest.Mock).mockResolvedValue(fileContent);
    }
    
    // Setup hook
    const { result } = renderHook(() => useFileUpload(validateJsonContent));
    
    // Add file to state
    const file = new File([fileContent], fileName, { type: fileType });
    act(() => {
      result.current.setFiles([file]);
    });
    
    // Process file
    let processResult: FileProcessingResult[] = [];
    await act(async () => {
      processResult = await result.current.processFiles();
    });
    
    return { result, processResult, file };
  };

  it("should initialize with empty state", () => {
    const { result } = renderHook(() => useFileUpload(validateJsonContent));
    
    expect(result.current.files).toEqual([]);
    expect(result.current.errors).toEqual([]);
    expect(result.current.isProcessing).toBe(false);
  });

  it("should process valid JSON file successfully", async () => {
    const validJson = '{"key": "value"}';
    const { processResult, file } = await setupFileProcessingTest(validJson, "valid.json");
    
    expect(processResult).toEqual([
      {
        success: true,
        fileName: "valid.json",
        content: validJson
      }
    ]);
    expect(readFileAsText).toHaveBeenCalledWith(file);
  });

  it("should process valid NDJSON file successfully", async () => {
    const validNdjson = '{"id": 1}\n{"id": 2}';
    const { processResult } = await setupFileProcessingTest(
      validNdjson, 
      "valid.ndjson", 
      "application/x-ndjson"
    );
    
    expect(processResult).toEqual([
      {
        success: true,
        fileName: "valid.ndjson",
        content: validNdjson
      }
    ]);
  });

  it("should reject invalid JSON file", async () => {
    const invalidJson = '{"key": value}'; // Missing quotes around value
    const { processResult, result } = await setupFileProcessingTest(invalidJson, "invalid.json");
    
    expect(processResult[0].success).toBe(false);
    expect(processResult[0].fileName).toBe("invalid.json");
    expect(processResult[0].error).toContain("Invalid JSON format");
    expect(result.current.errors.length).toBe(1);
    expect(result.current.errors[0]).toContain("Error in invalid.json");
  });

  it("should reject invalid NDJSON file", async () => {
    const invalidNdjson = '{"id": 1}\n{"id": 2,}';
    const { processResult, result } = await setupFileProcessingTest(
      invalidNdjson, 
      "invalid.ndjson", 
      "application/x-ndjson"
    );
    
    expect(processResult[0].success).toBe(false);
    expect(processResult[0].fileName).toBe("invalid.ndjson");
    expect(processResult[0].error).toContain("Invalid JSON format");
    expect(result.current.errors.length).toBe(1);
  });

  it("should handle file reading errors", async () => {
    const errorMessage = "Failed to read file";
    const { processResult, result } = await setupFileProcessingTest(
      "content", 
      "error.json",
      "application/json",
      () => Promise.reject(new Error(errorMessage))
    );
    
    expect(processResult[0].success).toBe(false);
    expect(processResult[0].fileName).toBe("error.json");
    expect(processResult[0].error).toContain(errorMessage);
    expect(result.current.errors.length).toBe(1);
    expect(result.current.errors[0]).toContain("Failed to process error.json");
  });

  it("should process multiple files correctly", async () => {
    // Mock implementation for multiple files
    (readFileAsText as jest.Mock).mockImplementation((file) => {
      if (file.name === "valid1.json") {
        return Promise.resolve('{"id": 1}');
      } else if (file.name === "valid2.json") {
        return Promise.resolve('{"id": 2}');
      } else {
        return Promise.resolve('{"id": 3,}'); // Invalid JSON
      }
    });
    
    const { result } = renderHook(() => useFileUpload(validateJsonContent));
    
    // Add multiple files to the state
    const files = [
      new File(['{"id": 1}'], "valid1.json", { type: "application/json" }),
      new File(['{"id": 2}'], "valid2.json", { type: "application/json" }),
      new File(['{"id": 3,}'], "invalid.json", { type: "application/json" })
    ];
    
    act(() => {
      result.current.setFiles(files);
    });
    
    // Process the files
    let processResult: FileProcessingResult[] = [];
    await act(async () => {
      processResult = await result.current.processFiles();
    });
    
    // Verify the results
    expect(processResult.length).toBe(3);
    expect(processResult[0].success).toBe(true);
    expect(processResult[1].success).toBe(true);
    expect(processResult[2].success).toBe(false);
    expect(result.current.errors.length).toBe(1);
  });

  it("should clear successful files after processing", async () => {
    // Mock implementation for different file types
    (readFileAsText as jest.Mock).mockImplementation((file) => {
      if (file.name === "valid.json") {
        return Promise.resolve('{"id": 1}');
      } else {
        return Promise.resolve('{"id": 2,}'); // Invalid JSON
      }
    });
    
    const { result } = renderHook(() => useFileUpload(validateJsonContent));
    
    // Add multiple files to the state
    const files = [
      new File(['{"id": 1}'], "valid.json", { type: "application/json" }),
      new File(['{"id": 2,}'], "invalid.json", { type: "application/json" })
    ];
    
    act(() => {
      result.current.setFiles(files);
    });
    
    // Process the files
    let processResult: FileProcessingResult[] = [];
    await act(async () => {
      processResult = await result.current.processFiles();
    });
    
    // Clear successful files
    act(() => {
      result.current.clearSuccessfulFiles(processResult);
    });
    
    // Verify that only the invalid file remains
    expect(result.current.files.length).toBe(1);
    expect(result.current.files[0].name).toBe("invalid.json");
  });

  it("should return empty array when no files to process", async () => {
    const { result } = renderHook(() => useFileUpload(validateJsonContent));
    
    let processResult: FileProcessingResult[] = [];
    await act(async () => {
      processResult = await result.current.processFiles();
    });
    
    expect(processResult).toEqual([]);
    expect(result.current.isProcessing).toBe(false);
  });
});
