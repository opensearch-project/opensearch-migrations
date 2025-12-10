/**
 * Tests for debug-logger utility
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createDebugLogger, isDevMode, formatLogMessage, noopLogger } from './debug-logger';

describe('debug-logger', () => {
  // Store original console methods
  const originalConsole = {
    log: console.log,
    info: console.info,
    warn: console.warn,
    error: console.error,
    group: console.group,
    groupEnd: console.groupEnd,
  };

  beforeEach(() => {
    // Mock console methods
    console.log = vi.fn();
    console.info = vi.fn();
    console.warn = vi.fn();
    console.error = vi.fn();
    console.group = vi.fn();
    console.groupEnd = vi.fn();
  });

  afterEach(() => {
    // Restore original console methods
    Object.assign(console, originalConsole);
    vi.restoreAllMocks();
  });

  describe('isDevMode', () => {
    it('should return a boolean', () => {
      expect(typeof isDevMode()).toBe('boolean');
    });
  });

  describe('formatLogMessage', () => {
    it('should format message with prefix', () => {
      expect(formatLogMessage('[Test]', 'Hello world', false)).toBe('[Test] Hello world');
    });

    it('should include timestamp when requested', () => {
      const result = formatLogMessage('[Test]', 'Hello world', true);
      expect(result).toMatch(/^\[\d{2}:\d{2}:\d{2}\.\d{3}\] \[Test\] Hello world$/);
    });

    it('should handle empty prefix', () => {
      expect(formatLogMessage('', 'Hello world', false)).toBe('Hello world');
    });
  });

  describe('noopLogger', () => {
    it('should have all logger methods and not output anything', () => {
      const methods = ['debug', 'info', 'warn', 'error', 'group', 'groupEnd', 'child'];
      methods.forEach(method => {
        expect(typeof noopLogger[method as keyof typeof noopLogger]).toBe('function');
      });

      // Test that no console methods are called
      noopLogger.debug('test');
      noopLogger.info('test');
      noopLogger.warn('test');
      noopLogger.error('test');
      noopLogger.group('test');
      noopLogger.groupEnd();

      expect(console.log).not.toHaveBeenCalled();
      expect(console.info).not.toHaveBeenCalled();
      expect(console.warn).not.toHaveBeenCalled();
      expect(console.error).not.toHaveBeenCalled();
      expect(console.group).not.toHaveBeenCalled();
      expect(console.groupEnd).not.toHaveBeenCalled();
    });

    it('should return noopLogger from child()', () => {
      expect(noopLogger.child('sub')).toBe(noopLogger);
    });
  });

  describe('createDebugLogger', () => {
    // Parameterized test for enabled/disabled states
    const enabledStates = [
      { enabled: true, shouldLog: true },
      { enabled: false, shouldLog: false },
    ];

    enabledStates.forEach(({ enabled, shouldLog }) => {
      describe(`when ${enabled ? 'enabled' : 'disabled'}`, () => {
        // Parameterized test for all log levels
        const logLevels = [
          { method: 'debug', consoleMethod: 'log' },
          { method: 'info', consoleMethod: 'info' },
          { method: 'warn', consoleMethod: 'warn' },
          { method: 'error', consoleMethod: 'error' },
        ] as const;

        logLevels.forEach(({ method, consoleMethod }) => {
          it(`should ${shouldLog ? '' : 'not '}output ${method} messages`, () => {
            const logger = createDebugLogger({ prefix: '[Test]', enabled });
            logger[method]('test message');
            
            if (shouldLog) {
              expect(console[consoleMethod]).toHaveBeenCalled();
            } else {
              expect(console[consoleMethod]).not.toHaveBeenCalled();
            }
          });
        });

        it(`should ${shouldLog ? '' : 'not '}handle group operations`, () => {
          const logger = createDebugLogger({ prefix: '[Test]', enabled });
          logger.group('Group Label');
          logger.groupEnd();
          
          if (shouldLog) {
            expect(console.group).toHaveBeenCalled();
            expect(console.groupEnd).toHaveBeenCalled();
          } else {
            expect(console.group).not.toHaveBeenCalled();
            expect(console.groupEnd).not.toHaveBeenCalled();
          }
        });
      });
    });

    describe('log level filtering', () => {
      const levelTests = [
        { minLevel: 'info', allowedMethods: ['info', 'warn', 'error'] },
        { minLevel: 'warn', allowedMethods: ['warn', 'error'] },
        { minLevel: 'error', allowedMethods: ['error'] },
      ] as const;

      levelTests.forEach(({ minLevel, allowedMethods }) => {
        it(`should only output ${allowedMethods.join(', ')} when minLevel is ${minLevel}`, () => {
          const logger = createDebugLogger({ 
            prefix: '[Test]', 
            enabled: true, 
            minLevel 
          });

          const allMethods = ['debug', 'info', 'warn', 'error'] as const;
          const consoleMethods = ['log', 'info', 'warn', 'error'] as const;

          allMethods.forEach((method, index) => {
            logger[method](`${method} message`);
            
            if (allowedMethods.includes(method as any)) {
              expect(console[consoleMethods[index]]).toHaveBeenCalled();
            } else {
              expect(console[consoleMethods[index]]).not.toHaveBeenCalled();
            }
          });
        });
      });
    });

    describe('child logger', () => {
      it('should create child logger with combined prefix and inherit settings', () => {
        const parent = createDebugLogger({ prefix: '[Parent]', enabled: true, minLevel: 'warn' });
        const child = parent.child('[Child]');
        
        child.debug('debug message');
        child.warn('warn message');
        
        expect(console.log).not.toHaveBeenCalled(); // debug filtered out
        expect(console.warn).toHaveBeenCalledWith(
          expect.stringContaining('[Parent][Child]')
        );
      });
    });

    describe('timestamp and prefix options', () => {
      it('should include timestamp when enabled', () => {
        const logger = createDebugLogger({ 
          prefix: '[Test]', 
          enabled: true, 
          includeTimestamp: true 
        });
        logger.debug('test message');
        expect(console.log).toHaveBeenCalledWith(
          expect.stringMatching(/\[\d{2}:\d{2}:\d{2}\.\d{3}\].*\[Test\]/)
        );
      });

      it('should use default prefix when not provided', () => {
        const logger = createDebugLogger({ enabled: true });
        logger.debug('test message');
        expect(console.log).toHaveBeenCalledWith('test message');
      });
    });
  });
});
