import { NextRequest, NextResponse } from "next/server";

/**
 * POST handler for creating a snapshot
 *
 * This endpoint proxies the request to the backend API to create a snapshot for a session.
 * It converts the Next.js route parameters to the appropriate backend API call.
 */
export async function POST(
  request: NextRequest,
  { params }: { params: { session_name: string } },
) {
  const { session_name } = params;

  if (!session_name) {
    return NextResponse.json(
      { error: "Session name is required" },
      { status: 400 },
    );
  }

  try {
    // Make a request to the backend API
    const response = await fetch(
      `${process.env.NEXT_PUBLIC_BACKEND_API || ""}/sessions/${session_name}/snapshot/create`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        // Forward any request body if needed
        body: request.body ? await request.text() : JSON.stringify({}),
      },
    );

    // If the response is not ok, throw an error
    if (!response.ok) {
      const errorText = await response.text();
      return NextResponse.json(
        { error: `Failed to create snapshot: ${errorText}` },
        { status: response.status },
      );
    }

    // Return the successful response
    const data = await response.json().catch(() => ({}));
    return NextResponse.json(data, { status: 200 });
  } catch (error) {
    console.error("Error creating snapshot:", error);
    return NextResponse.json(
      {
        error: "Failed to create snapshot",
        details: error instanceof Error ? error.message : "Unknown error",
      },
      { status: 500 },
    );
  }
}
