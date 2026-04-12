"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var approvedMutators_1 = require("../src/approvedMutators");
var helpers_1 = require("./helpers");
var config_processor_1 = require("@opensearch-migrations/config-processor");
describe('approvedMutators', function () {
    var config = (0, helpers_1.loadFullMigrationConfig)();
    it('all mutators produce valid configs', function () {
        var transformer = new config_processor_1.MigrationConfigTransformer();
        var _loop_1 = function (mutator) {
            var mutated = mutator.apply(config);
            expect(function () { return transformer.validateInput(mutated); }).not.toThrow();
        };
        for (var _i = 0, defaultMutatorRegistry_1 = approvedMutators_1.defaultMutatorRegistry; _i < defaultMutatorRegistry_1.length; _i++) {
            var mutator = defaultMutatorRegistry_1[_i];
            _loop_1(mutator);
        }
    });
    it('findMutators returns focus-change safe mutators', function () {
        var result = (0, approvedMutators_1.findMutators)(approvedMutators_1.defaultMutatorRegistry, 'safe', 'focus-change');
        expect(result.length).toBeGreaterThanOrEqual(1);
        expect(result[0].id).toBe('proxy-noCapture-toggle');
    });
    it('findMutators returns immediate-dependent-change safe mutators', function () {
        var result = (0, approvedMutators_1.findMutators)(approvedMutators_1.defaultMutatorRegistry, 'safe', 'immediate-dependent-change');
        expect(result.length).toBeGreaterThanOrEqual(1);
        expect(result[0].id).toBe('replayer-speedupFactor');
    });
    it('findMutators returns transitive-dependent-change safe mutators', function () {
        var result = (0, approvedMutators_1.findMutators)(approvedMutators_1.defaultMutatorRegistry, 'safe', 'transitive-dependent-change');
        expect(result.length).toBeGreaterThanOrEqual(1);
        expect(result[0].id).toBe('rfs-maxConnections');
    });
    it('findMutators returns empty for non-matching criteria', function () {
        var result = (0, approvedMutators_1.findMutators)(approvedMutators_1.defaultMutatorRegistry, 'impossible', 'focus-change');
        expect(result).toHaveLength(0);
    });
});
