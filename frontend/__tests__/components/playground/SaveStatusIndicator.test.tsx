import React from "react";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import SaveStatusIndicator from "@/components/playground/SaveStatusIndicator";
import { SaveStatus } from "@/types/SaveStatus";
import { formatDistanceToNow } from "date-fns";
import { IAnnotation } from "react-ace/lib/types";

// Make mocks for the external libraries
jest.mock("date-fns", () => ({
  formatDistanceToNow: jest.fn(),
}));
jest.mock("@cloudscape-design/components/status-indicator", () => {
  return {
    __esModule: true,
    default: ({
      type,
      children,
    }: {
      type: string;
      children: React.ReactNode;
    }) => (
      <div data-testid="status-indicator" data-type={type}>
        {children}
      </div>
    ),
  };
});

describe("SaveStatusIndicator", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe("status indicator display based off of state", () => {
    it("should render with success styling when status is SAVED", () => {
      const savedState = {
        status: SaveStatus.SAVED,
        savedAt: null,
        errors: [],
      };

      render(<SaveStatusIndicator state={savedState} />);
      const statusIndicator = screen.getByTestId("status-indicator");

      expect(statusIndicator).toBeInTheDocument();
      expect(statusIndicator).toHaveAttribute("data-type", "success");
      expect(statusIndicator).toHaveTextContent("Saved");
    });

    it("should render with error styling when status is BLOCKED", () => {
      const blockedState = {
        status: SaveStatus.BLOCKED,
        savedAt: null,
        errors: [],
      };

      render(<SaveStatusIndicator state={blockedState} />);
      const statusIndicator = screen.getByTestId("status-indicator");

      expect(statusIndicator).toBeInTheDocument();
      expect(statusIndicator).toHaveAttribute("data-type", "error");
      expect(statusIndicator).toHaveTextContent("Blocked");
    });

    it("should render with in-progress styling when status is UNSAVED", () => {
      const unsavedState = {
        status: SaveStatus.UNSAVED,
        savedAt: null,
        errors: [],
      };

      render(<SaveStatusIndicator state={unsavedState} />);
      const statusIndicator = screen.getByTestId("status-indicator");

      expect(statusIndicator).toBeInTheDocument();
      expect(statusIndicator).toHaveAttribute("data-type", "in-progress");
      expect(statusIndicator).toHaveTextContent("Unsaved");
    });

    it("should default to in-progress styling when state is undefined", () => {
      // @ts-ignore - Intentionally passing undefined for testing
      render(<SaveStatusIndicator state={undefined} />);
      const statusIndicator = screen.getByTestId("status-indicator");

      expect(statusIndicator).toBeInTheDocument();
      expect(statusIndicator).toHaveAttribute("data-type", "in-progress");
      expect(statusIndicator).toHaveTextContent("Unsaved");
    });

    it("should default to in-progress styling when state.status is null", () => {
      const nullState = {
        status: null,
        savedAt: null,
        errors: [],
      };

      // @ts-ignore - Intentionally passing null status for testing
      render(<SaveStatusIndicator state={nullState} />);
      const statusIndicator = screen.getByTestId("status-indicator");

      expect(statusIndicator).toBeInTheDocument();
      expect(statusIndicator).toHaveAttribute("data-type", "in-progress");
      expect(statusIndicator).toHaveTextContent("Unsaved");
    });
  });

  describe("save status indicator displaying time formatting", () => {
    it("should display the correct time format when savedAt is provided", () => {
      const mockTime = "5 minutes";
      (formatDistanceToNow as jest.Mock).mockReturnValue(mockTime);

      const savedDate = new Date();
      const savedState = {
        status: SaveStatus.SAVED,
        savedAt: savedDate,
        errors: [],
      };

      render(<SaveStatusIndicator state={savedState} />);

      expect(formatDistanceToNow).toHaveBeenCalledWith(savedDate);
      expect(screen.getByText(`Saved ${mockTime} ago`)).toBeInTheDocument();
    });

    it("should not display time when savedAt is null", () => {
      const savedState = {
        status: SaveStatus.SAVED,
        savedAt: null,
        errors: [],
      };

      render(<SaveStatusIndicator state={savedState} />);

      expect(formatDistanceToNow).not.toHaveBeenCalled();
      expect(screen.getByText("Saved")).toBeInTheDocument();
      expect(screen.queryByText(/ago/)).not.toBeInTheDocument();
    });
  });

  describe("save status indicator displaying error messages", () => {
    it("should display correct message with single error", () => {
      const blockedState = {
        status: SaveStatus.BLOCKED,
        savedAt: null,
        errors: [
          { row: 1, column: 1, type: "error" as const, text: "Test error" },
        ] as IAnnotation[],
      };

      render(<SaveStatusIndicator state={blockedState} />);

      expect(screen.getByText("Blocked (1 error)")).toBeInTheDocument();
    });

    it("should display correct message with multiple errors", () => {
      const blockedState = {
        status: SaveStatus.BLOCKED,
        savedAt: null,
        errors: [
          { row: 1, column: 1, type: "error" as const, text: "First error" },
          { row: 2, column: 1, type: "error" as const, text: "Second error" },
          { row: 3, column: 1, type: "error" as const, text: "Third error" },
        ] as IAnnotation[],
      };

      render(<SaveStatusIndicator state={blockedState} />);

      expect(screen.getByText("Blocked (3 errors)")).toBeInTheDocument();
    });

    it("should display only 'Blocked' when there are no errors", () => {
      const blockedState = {
        status: SaveStatus.BLOCKED,
        savedAt: null,
        errors: [],
      };

      render(<SaveStatusIndicator state={blockedState} />);

      expect(screen.getByText("Blocked")).toBeInTheDocument();
      expect(screen.queryByText(/\(\d+ errors?\)/)).not.toBeInTheDocument();
    });
  });
});
