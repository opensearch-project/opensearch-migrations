"use strict";
var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
var __generator = (this && this.__generator) || function (thisArg, body) {
    var _ = { label: 0, sent: function() { if (t[0] & 1) throw t[1]; return t[1]; }, trys: [], ops: [] }, f, y, t, g = Object.create((typeof Iterator === "function" ? Iterator : Object).prototype);
    return g.next = verb(0), g["throw"] = verb(1), g["return"] = verb(2), typeof Symbol === "function" && (g[Symbol.iterator] = function() { return this; }), g;
    function verb(n) { return function (v) { return step([n, v]); }; }
    function step(op) {
        if (f) throw new TypeError("Generator is already executing.");
        while (g && (g = 0, op[0] && (_ = 0)), _) try {
            if (f = 1, y && (t = op[0] & 2 ? y["return"] : op[0] ? y["throw"] || ((t = y["return"]) && t.call(y), 0) : y.next) && !(t = t.call(y, op[1])).done) return t;
            if (y = 0, t) op = [op[0] & 2, t.value];
            switch (op[0]) {
                case 0: case 1: t = op; break;
                case 4: _.label++; return { value: op[1], done: false };
                case 5: _.label++; y = op[1]; op = [0]; continue;
                case 7: op = _.ops.pop(); _.trys.pop(); continue;
                default:
                    if (!(t = _.trys, t = t.length > 0 && t[t.length - 1]) && (op[0] === 6 || op[0] === 2)) { _ = 0; continue; }
                    if (op[0] === 3 && (!t || (op[1] > t[0] && op[1] < t[3]))) { _.label = op[1]; break; }
                    if (op[0] === 6 && _.label < t[1]) { _.label = t[1]; t = op; break; }
                    if (t && _.label < t[2]) { _.label = t[2]; _.ops.push(op); break; }
                    if (t[2]) _.ops.pop();
                    _.trys.pop(); continue;
            }
            op = body.call(thisArg, _);
        } catch (e) { op = [6, e]; y = 0; } finally { f = t = 0; }
        if (op[0] & 5) throw op[1]; return { value: op[0] ? op[1] : void 0, done: true };
    }
};
Object.defineProperty(exports, "__esModule", { value: true });
var matrixExpander_1 = require("../src/matrixExpander");
var checksumReporter_1 = require("../src/checksumReporter");
var approvedMutators_1 = require("../src/approvedMutators");
var helpers_1 = require("./helpers");
describe('expandMatrix', function () {
    it('focus-change: proxy mutation reruns proxy + all downstream', function () { return __awaiter(void 0, void 0, void 0, function () {
        var config, report, spec, cases;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    config = (0, helpers_1.loadFullMigrationConfig)();
                    return [4 /*yield*/, (0, checksumReporter_1.buildChecksumReport)(config)];
                case 1:
                    report = _a.sent();
                    spec = {
                        focus: 'proxy:capture-proxy',
                        select: [{ changeClass: 'safe', patterns: ['focus-change'] }],
                    };
                    return [4 /*yield*/, (0, matrixExpander_1.expandMatrix)(spec, report, approvedMutators_1.defaultMutatorRegistry, config, report)];
                case 2:
                    cases = _a.sent();
                    expect(cases).toHaveLength(1);
                    expect(cases[0].name).toBe('proxy:capture-proxy/focus-change/proxy-noCapture-toggle');
                    // noCapture is checksumFor(snapshot, replayer), so proxy + snapshot + migration + replay should rerun
                    expect(cases[0].expect.reran).toContain('proxy:capture-proxy');
                    expect(cases[0].expect.reran).toContain('snapshot:source-snap1');
                    expect(cases[0].expect.reran).toContain('replay:capture-proxy-target-replay1');
                    // kafka should be unchanged (upstream prerequisite)
                    expect(cases[0].expect.unchanged).toContain('kafka:default');
                    return [2 /*return*/];
            }
        });
    }); });
    it('immediate-dependent-change: replayer mutation reruns only replayer', function () { return __awaiter(void 0, void 0, void 0, function () {
        var config, report, spec, cases;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    config = (0, helpers_1.loadFullMigrationConfig)();
                    return [4 /*yield*/, (0, checksumReporter_1.buildChecksumReport)(config)];
                case 1:
                    report = _a.sent();
                    spec = {
                        focus: 'proxy:capture-proxy',
                        select: [{ changeClass: 'safe', patterns: ['immediate-dependent-change'] }],
                    };
                    return [4 /*yield*/, (0, matrixExpander_1.expandMatrix)(spec, report, approvedMutators_1.defaultMutatorRegistry, config, report)];
                case 2:
                    cases = _a.sent();
                    expect(cases).toHaveLength(1);
                    expect(cases[0].name).toBe('proxy:capture-proxy/immediate-dependent-change/replayer-speedupFactor');
                    // Only the replayer should rerun (it has no downstream dependents)
                    expect(cases[0].expect.reran).toEqual(['replay:capture-proxy-target-replay1']);
                    // Everything else unchanged
                    expect(cases[0].expect.unchanged).toContain('proxy:capture-proxy');
                    expect(cases[0].expect.unchanged).toContain('kafka:default');
                    expect(cases[0].expect.unchanged).toContain('snapshot:source-snap1');
                    return [2 /*return*/];
            }
        });
    }); });
    it('transitive-dependent-change: migration mutation reruns only migration', function () { return __awaiter(void 0, void 0, void 0, function () {
        var config, report, spec, cases;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    config = (0, helpers_1.loadFullMigrationConfig)();
                    return [4 /*yield*/, (0, checksumReporter_1.buildChecksumReport)(config)];
                case 1:
                    report = _a.sent();
                    spec = {
                        focus: 'proxy:capture-proxy',
                        select: [{ changeClass: 'safe', patterns: ['transitive-dependent-change'] }],
                    };
                    return [4 /*yield*/, (0, matrixExpander_1.expandMatrix)(spec, report, approvedMutators_1.defaultMutatorRegistry, config, report)];
                case 2:
                    cases = _a.sent();
                    expect(cases).toHaveLength(1);
                    expect(cases[0].name).toBe('proxy:capture-proxy/transitive-dependent-change/rfs-maxConnections');
                    // Only the migration should rerun
                    expect(cases[0].expect.reran).toEqual(['snapshotMigration:source-snap1']);
                    expect(cases[0].expect.unchanged).toContain('proxy:capture-proxy');
                    expect(cases[0].expect.unchanged).toContain('snapshot:source-snap1');
                    return [2 /*return*/];
            }
        });
    }); });
    it('returns empty for no matching mutators', function () { return __awaiter(void 0, void 0, void 0, function () {
        var config, report, spec, cases;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    config = (0, helpers_1.loadFullMigrationConfig)();
                    return [4 /*yield*/, (0, checksumReporter_1.buildChecksumReport)(config)];
                case 1:
                    report = _a.sent();
                    spec = {
                        focus: 'proxy:capture-proxy',
                        select: [{ changeClass: 'impossible', patterns: ['focus-change'] }],
                    };
                    return [4 /*yield*/, (0, matrixExpander_1.expandMatrix)(spec, report, approvedMutators_1.defaultMutatorRegistry, config, report)];
                case 2:
                    cases = _a.sent();
                    expect(cases).toHaveLength(0);
                    return [2 /*return*/];
            }
        });
    }); });
    it('requireFullCoverage throws when patterns have no mutators', function () { return __awaiter(void 0, void 0, void 0, function () {
        var config, report, spec;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    config = (0, helpers_1.loadFullMigrationConfig)();
                    return [4 /*yield*/, (0, checksumReporter_1.buildChecksumReport)(config)];
                case 1:
                    report = _a.sent();
                    spec = {
                        focus: 'proxy:capture-proxy',
                        select: [{
                                changeClass: 'gated',
                                patterns: ['focus-gated-change'],
                                requireFullCoverage: true,
                            }],
                    };
                    return [4 /*yield*/, expect((0, matrixExpander_1.expandMatrix)(spec, report, approvedMutators_1.defaultMutatorRegistry, config, report))
                            .rejects.toThrow('requireFullCoverage')];
                case 2:
                    _a.sent();
                    return [2 /*return*/];
            }
        });
    }); });
});
