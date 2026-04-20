import { describe, it, expect } from 'vitest';
import { createMetrics, incrementMetric, flushMetrics } from './metrics';

describe('metrics', () => {
  it('createMetrics returns an empty map', () => {
    const acc = createMetrics();
    expect(acc.size).toBe(0);
  });

  it('incrementMetric sets count to 1 on first call', () => {
    const acc = createMetrics();
    incrementMetric(acc, 'terms_offset');
    expect(acc.get('terms_offset')).toBe(1);
  });

  it('incrementMetric increments on repeated calls', () => {
    const acc = createMetrics();
    incrementMetric(acc, 'range_boundary');
    incrementMetric(acc, 'range_boundary');
    incrementMetric(acc, 'range_boundary');
    expect(acc.get('range_boundary')).toBe(3);
  });

  it('incrementMetric tracks multiple metrics independently', () => {
    const acc = createMetrics();
    incrementMetric(acc, 'terms_offset');
    incrementMetric(acc, 'date_range_gap');
    incrementMetric(acc, 'terms_offset');
    expect(acc.get('terms_offset')).toBe(2);
    expect(acc.get('date_range_gap')).toBe(1);
  });

  it('flushMetrics does nothing when accumulator is empty', () => {
    const acc = createMetrics();
    const msg = new Map<string, any>();
    flushMetrics(acc, msg);
    expect(msg.has('_metrics')).toBe(false);
  });

  it('flushMetrics writes accumulated metrics onto the message map', () => {
    const acc = createMetrics();
    incrementMetric(acc, 'terms_offset');
    incrementMetric(acc, 'date_range_gap_compound');
    const msg = new Map<string, any>();
    flushMetrics(acc, msg);
    const metrics = msg.get('_metrics');
    expect(metrics).toBeDefined();
    expect(metrics.get('terms_offset')).toBe(1);
    expect(metrics.get('date_range_gap_compound')).toBe(1);
  });
});
