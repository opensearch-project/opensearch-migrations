import { setupServer } from "msw/node";
export const server = setupServer();

// Setup/teardown lifecycle hooks for MSW
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
