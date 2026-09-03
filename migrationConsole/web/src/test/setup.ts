import "@testing-library/jest-dom/vitest";

import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll } from "vitest";

import { server } from "./server";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
  window.localStorage.clear();
  window.sessionStorage.clear();
});
afterAll(() => server.close());


class TestEventSource {
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;

  constructor() {
    queueMicrotask(() => this.onopen?.());
  }

  addEventListener() {}
  close() {}
}


Object.defineProperty(globalThis, "EventSource", {
  configurable: true,
  value: TestEventSource,
});
