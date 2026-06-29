import { crdName } from '../src/crdNaming';

describe('crdName', () => {
    it('joins parts with - and lowercases', () => {
        expect(crdName('Source1', 'Target1', 'snap', 'migration-0'))
            .toBe('source1-target1-snap-migration-0');
    });
    it('replaces invalid char runs with a single dash', () => {
        expect(crdName('a b@@c')).toBe('a-b-c');
    });
    it('collapses repeated dashes', () => {
        expect(crdName('a---b')).toBe('a-b');
    });
    it('strips leading and trailing dashes and dots', () => {
        expect(crdName('-.a.b.-')).toBe('a.b');
    });
    it('keeps dots inside the name', () => {
        expect(crdName('es7.10', 'os2.11')).toBe('es7.10-os2.11');
    });
    it('returns empty string when nothing survives (matches makeCrdName today)', () => {
        expect(crdName('---')).toBe('');
    });
});
