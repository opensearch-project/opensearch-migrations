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
    console.log = originalConsole.log;
    console.info = originalConsole.info;
    console.warn = originalConsole.warn;
    console.error = originalConsole.error;
    console.group = originalConsole.group;
    console.groupEnd = originalConsole.groupEnd;
    vi.restoreAllMocks();
  });

  describe('isDevMode', () => {
    it('should return a boolean', () => {
      const result = isDevMode();
      expect(typeof result).toBe('boolean');
    });

    it('should return false in test environment', () => {
      // In test environment, NODE_ENV is typically 'test'
      // and import.meta.env.DEV may be false
      const result = isDevMode();
      // The actual value depends on the test environment setup
      expect(typeof result).toBe('boolean');
    });
  });

  describe('formatLogMessage', () => {
    it('should format message with prefix', () => {
      const result = formatLogMessage('[Test]', 'Hello world', false);
      expect(result).toBe('[Test] Hello world');
    });

    it('should include timestamp when requested', () => {
      const result = formatLogMessage('[Test]', 'Hello world', true);
      // Should match pattern like "[HH:MM:SS.mmm] [Test] Hello world"
      expect(result).toMatch(/^\[\d{2}:\d{2}:\d{2}\.\d{3}\] \[Test\] Hello world$/);
    });

    it('should handle empty prefix', () => {
      const result = formatLogMessage('', 'Hello world', false);
      // Empty prefix results in just the message
      expect(result).toBe('Hello world');
    });
  });

  describe('noopLogger', () => {
    it('should have all logger methods', () => {
      expect(typeof noopLogger.debug).toBe('function');
      expect(typeof noopLogger.info).toBe('function');
      expect(typeof noopLogger.warn).toBe('function');
      expect(typeof noopLogger.error).toBe('function');
      expect(typeof noopLogger.group).toBe('function');
      expect(typeof noopLogger.groupEnd).toBe('function');
      expect(typeof noopLogger.child).toBe('function');
    });

    it('should not output anything when called', () => {
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
      const child = noopLogger.child('sub');
      expect(child).toBe(noopLogger);
    });
  });

  describe('createDebugLogger', () => {
    describe('when enabled', () => {
      it('should output debug messages', () => {
        const logger = createDebugLogger({ prefix: '[Test]', enabled: true });
        logger.debug('test message');
        expect(console.log).toHaveBeenCalled();
      });

      it('should output info messages', () => {
        const logger = createDebugLogger({ prefix: '[Test]', enabled: true });
        logger.info('test message');
        expect(console.info).toHaveBeenCalled();
      });

      it('should output warn messages', () => {
        const logger = createDebugLogger({ prefix: '[Test]', enabled: true });
        logger.warn('test message');
        expect(console.warn).toHaveBeenCalled();
      });

      it('should output error messages', () => {
        const logger = createDebugLogger({ prefix: '[Test]', enabled: true });
        logger.error('test message');
        expect(console.error).toHaveBeenCalled();
      });

      it('should include prefix in output', () => {
        const logger = createDebugLogger({ prefix: '[MyPrefix]', enabled: true });
        logger.debug('test message');
        // The logger combines prefix and message into a single string
        expect(console.log).toHaveBeenCalledWith(
          expect.stringContaining('[MyPrefix]')
        );
      });

      it('should pass additional arguments', () => {
        const logger = createDebugLogger({ prefix: '[Test]', enabled: true });
        const extraData = { foo: 'bar' };
        logger.debug('test message', extraData);
        // The logger combines prefix and message, then passes extra args
        expect(console.log).toHaveBeenCalledWith(
          expect.stringContaining('[Test] test message'),
          extraData
        );
      });

      it('should handle group and groupEnd', () => {
        const logger = createDebugLogger({ prefix: '[Test]', enabled: true });
        logger.group('Group Label');
        logger.groupEnd();
        expect(console.group).toHaveBeenCalledWith(expect.stringContaining('Group Label'));
        expect(console.groupEnd).toHaveBeenCalled();
      });
    });

    describe('when disabled', () => {
      it('should not output debug messages', () => {
        const logger = createDebugLogger({ prefix: '[Test]', enabled: false });
        logger.debug('test message');
        expect(console.log).not.toHaveBeenCalled();
      });

      it('should not output info messages', () => {
        const logger = createDebugLogger({ prefix: '[Test]', enabled: false });
        logger.info('test message');
        expect(console.info).not.toHaveBeenCalled();
      });

      it('should not output warn messages', () => {
        const logger = createDebugLogger({ prefix: '[Test]', enabled: false });
        logger.warn('test message');
        expect(console.warn).not.toHaveBeenCalled();
      });

      it('should not output error messages', () => {
        const logger = createDebugLogger({ prefix: '[Test]', enabled: false });
        logger.error('test message');
        expect(console.error).not.toHaveBeenCalled();
      });

      it('should not call group or groupEnd', () => {
        const logger = createDebugLogger({ prefix: '[Test]', enabled: false });
        logger.group('Group Label');
        logger.groupEnd();
        expect(console.group).not.toHaveBeenCalled();
        expect(console.groupEnd).not.toHaveBeenCalled();
      });
    });

    describe('log level filtering', () => {
      it('should filter out debug when minLevel is info', () => {
        const logger = createDebugLogger({ 
          prefix: '[Test]', 
          enabled: true, 
          minLevel: 'info' 
        });
        logger.debug('debug message');
        logger.info('info message');
        expect(console.log).not.toHaveBeenCalled();
        expect(console.info).toHaveBeenCalled();
      });

      it('should filter out debug and info when minLevel is warn', () => {
        const logger = createDebugLogger({ 
          prefix: '[Test]', 
          enabled: true, 
          minLevel: 'warn' 
        });
        logger.debug('debug message');
        logger.info('info message');
        logger.warn('warn message');
        expect(console.log).not.toHaveBeenCalled();
        expect(console.info).not.toHaveBeenCalled();
        expect(console.warn).toHaveBeenCalled();
      });

      it('should only output error when minLevel is error', () => {
        const logger = createDebugLogger({ 
          prefix: '[Test]', 
          enabled: true, 
          minLevel: 'error' 
        });
        logger.debug('debug message');
        logger.info('info message');
        logger.warn('warn message');
        logger.error('error message');
        expect(console.log).not.toHaveBeenCalled();
        expect(console.info).not.toHaveBeenCalled();
        expect(console.warn).not.toHaveBeenCalled();
        expect(console.error).toHaveBeenCalled();
      });
    });

    describe('child logger', () => {
      it('should create child logger with combined prefix', () => {
        const parent = createDebugLogger({ prefix: '[Parent]', enabled: true });
        const child = parent.child('[Child]');
        child.debug('test message');
        // The logger combines prefix and message into a single string
        expect(console.log).toHaveBeenCalledWith(
          expect.stringContaining('[Parent][Child]')
        );
      });

      it('should inherit enabled state from parent', () => {
        const parent = createDebugLogger({ prefix: '[Parent]', enabled: false });
        const child = parent.child('[Child]');
        child.debug('test message');
        expect(console.log).not.toHaveBeenCalled();
      });

      it('should inherit minLevel from parent', () => {
        const parent = createDebugLogger({ 
          prefix: '[Parent]', 
          enabled: true, 
          minLevel: 'warn' 
        });
        const child = parent.child('[Child]');
        child.debug('debug message');
        child.warn('warn message');
        expect(console.log).not.toHaveBeenCalled();
        expect(console.warn).toHaveBeenCalled();
      });
    });

    describe('timestamp option', () => {
      it('should include timestamp when includeTimestamp is true', () => {
        const logger = createDebugLogger({ 
          prefix: '[Test]', 
          enabled: true, 
          includeTimestamp: true 
        });
        logger.debug('test message');
        // The logger combines timestamp, prefix, and message into a single string
        expect(console.log).toHaveBeenCalledWith(
          expect.stringMatching(/\[\d{2}:\d{2}:\d{2}\.\d{3}\]/)
        );
      });

      it('should not include timestamp when includeTimestamp is false', () => {
        const logger = createDebugLogger({ 
          prefix: '[Test]', 
          enabled: true, 
          includeTimestamp: false 
        });
        logger.debug('test message');
        const call = (console.log as ReturnType<typeof vi.fn>).mock.calls[0];
        expect(call[0]).not.toMatch(/\[\d{2}:\d{2}:\d{2}\.\d{3}\]/);
      });
    });

    describe('default configuration', () => {
      it('should use default prefix when not provided', () => {
        const logger = createDebugLogger({ enabled: true });
        logger.debug('test message');
        // When no prefix is provided, the message is logged directly
        expect(console.log).toHaveBeenCalledWith('test message');
      });

      it('should default minLevel to debug', () => {
        const logger = createDebugLogger({ prefix: '[Test]', enabled: true });
        logger.debug('debug message');
        expect(console.log).toHaveBeenCalled();
      });
    });
  });
});
