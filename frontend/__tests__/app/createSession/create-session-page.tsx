import React from "react";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import CreateSessionPage from "@/app/createSession/page";
import { server } from "@tests/__utils__/mswServer";
import { http, HttpResponse } from "msw";

describe("CreateSessionPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  const createSessionWithName = async (name: string) => {
    const input = screen.getByPlaceholderText(/enter a session name/i);
    fireEvent.change(input, { target: { value: name } });

    const button = screen.getByRole("button", { name: /create session/i });
    fireEvent.click(button);

    return { input, button };
  };

  it("disables the button and shows spinner during submission", async () => {
    let requestReceived = false;
    server.use(
      http.post("http://localhost/sessions", async ({ request }) => {
        requestReceived = true;
        const body = await request.json();
        console.info("***Body:\n" + body);
        // expect(body.name).toBe("Test Session");
        return HttpResponse.json({});
      })
    );

    render(<CreateSessionPage />);
    const { button } = await createSessionWithName("Test Session");

    expect(button).toBeDisabled();
    expect(screen.getByTestId("session-spinner")).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText(/session created successfully/i)).toBeInTheDocument();
    });

    expect(requestReceived).toBe(true);
  });

  it("shows error alert if the API returns a structured error", async () => {
    server.use(
      http.post("http://localhost/sessions", () =>
        HttpResponse.json(
          { error: { detail: "Session name already exists" } },
          { status: 400 }
        )
      )
    );

    render(<CreateSessionPage />);
    await createSessionWithName("Duplicate");

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(/session name already exists/i);
    });
  });

  it("shows a fallback error message on network failure", async () => {
    server.use(
      http.post("http://localhost/sessions", () => {
        throw new Error("Network Error");
      })
    );

    render(<CreateSessionPage />);
    await createSessionWithName("Will Fail");

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(/network error/i);
    });
  });

  it("requires a session name before enabling the button", () => {
    render(<CreateSessionPage />);
    const button = screen.getByRole("button", { name: /create session/i });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-describedby");
  });
});
