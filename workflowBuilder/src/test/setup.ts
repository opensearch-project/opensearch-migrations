/**
 * Test Setup
 * 
 * Configure testing environment for Vitest with React Testing Library
 */

import '@testing-library/jest-dom';

// Mock window.matchMedia for Cloudscape components
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

// Mock ResizeObserver for Cloudscape components
class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

window.ResizeObserver = ResizeObserverMock;

// Mock IntersectionObserver
class IntersectionObserverMock {
  root = null;
  rootMargin = '';
  thresholds = [];
  
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() { return []; }
}

window.IntersectionObserver = IntersectionObserverMock as unknown as typeof IntersectionObserver;
