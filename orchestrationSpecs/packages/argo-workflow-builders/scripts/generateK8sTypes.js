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
var json_schema_to_typescript_1 = require("json-schema-to-typescript");
var fs = require("fs");
var path = require("path");
// Helper function to recursively update $ref pointers
function updateRefs(obj) {
    if (obj === null || typeof obj !== 'object') {
        return obj;
    }
    if (Array.isArray(obj)) {
        return obj.map(updateRefs);
    }
    var updated = {};
    for (var _i = 0, _a = Object.entries(obj); _i < _a.length; _i++) {
        var _b = _a[_i], key = _b[0], value = _b[1];
        if (key === '$ref' && typeof value === 'string') {
            // Convert #/components/schemas/... to #/definitions/...
            updated[key] = value.replace('#/components/schemas/', '#/definitions/');
        }
        else {
            updated[key] = updateRefs(value);
        }
    }
    return updated;
}
function generateTypes(schemaDir_1, outputFile_1, resources_1) {
    return __awaiter(this, arguments, void 0, function (schemaDir, outputFile, resources, additionalTypes) {
        var outputDir, allDefinitions, schemaFiles, resourceSchemaKeys, _i, resources_2, resource, filename, _a, schemaFiles_1, filename, schemaPath, schemaContent, definitions, _loop_1, _b, resources_3, resource, updatedDefinitions, masterSchema, allTypes, typeNameMap, interfaceRegex, match, schemaKey, typeName, resourceAliases, additionalAliases, finalContent;
        var _c;
        if (additionalTypes === void 0) { additionalTypes = []; }
        return __generator(this, function (_d) {
            switch (_d.label) {
                case 0:
                    outputDir = path.dirname(outputFile);
                    if (!fs.existsSync(outputDir)) {
                        fs.mkdirSync(outputDir, { recursive: true });
                    }
                    allDefinitions = {};
                    schemaFiles = new Set();
                    resourceSchemaKeys = [];
                    for (_i = 0, resources_2 = resources; _i < resources_2.length; _i++) {
                        resource = resources_2[_i];
                        filename = resource.apiVersion === 'v1'
                            ? 'core-v1-schema.json'
                            : "".concat(resource.apiVersion.replace(/\//g, '-'), "-schema.json");
                        schemaFiles.add(filename);
                    }
                    // Load and merge all definitions
                    for (_a = 0, schemaFiles_1 = schemaFiles; _a < schemaFiles_1.length; _a++) {
                        filename = schemaFiles_1[_a];
                        schemaPath = path.join(schemaDir, filename);
                        if (!fs.existsSync(schemaPath)) {
                            console.warn("Schema not found: ".concat(schemaPath));
                            continue;
                        }
                        schemaContent = JSON.parse(fs.readFileSync(schemaPath, 'utf-8'));
                        definitions = ((_c = schemaContent.components) === null || _c === void 0 ? void 0 : _c.schemas) || {};
                        Object.assign(allDefinitions, definitions);
                    }
                    _loop_1 = function (resource) {
                        var schemaKey = Object.keys(allDefinitions).find(function (key) { return key.endsWith(".".concat(resource.kind)); });
                        if (schemaKey) {
                            resourceSchemaKeys.push({ kind: resource.kind, schemaKey: schemaKey });
                        }
                        else {
                            console.warn("".concat(resource.kind, " not found in schemas"));
                        }
                    };
                    // Find schema keys for each resource
                    for (_b = 0, resources_3 = resources; _b < resources_3.length; _b++) {
                        resource = resources_3[_b];
                        _loop_1(resource);
                    }
                    updatedDefinitions = updateRefs(allDefinitions);
                    masterSchema = {
                        type: 'object',
                        definitions: updatedDefinitions
                    };
                    return [4 /*yield*/, (0, json_schema_to_typescript_1.compile)(masterSchema, 'K8sTypes', {
                            bannerComment: '/* Generated Kubernetes type definitions */',
                            unreachableDefinitions: true,
                            strictIndexSignatures: false,
                        })];
                case 1:
                    allTypes = _d.sent();
                    typeNameMap = new Map();
                    interfaceRegex = /via the `definition` "([^"]+)"\.[\s\S]*?\nexport interface (\w+)/g;
                    while ((match = interfaceRegex.exec(allTypes)) !== null) {
                        schemaKey = match[1], typeName = match[2];
                        typeNameMap.set(schemaKey, typeName);
                    }
                    console.log('Found type mappings for resources:');
                    resourceSchemaKeys.forEach(function (_a) {
                        var kind = _a.kind, schemaKey = _a.schemaKey;
                        console.log("  ".concat(kind, " -> ").concat(schemaKey, " -> ").concat(typeNameMap.get(schemaKey) || 'NOT FOUND'));
                    });
                    console.log('\nFound type mappings for additional types:');
                    additionalTypes.forEach(function (_a) {
                        var name = _a.name, schemaKey = _a.schemaKey;
                        console.log("  ".concat(name, " -> ").concat(schemaKey, " -> ").concat(typeNameMap.get(schemaKey) || 'NOT FOUND'));
                    });
                    resourceAliases = resourceSchemaKeys.map(function (_a) {
                        var kind = _a.kind, schemaKey = _a.schemaKey;
                        var generatedTypeName = typeNameMap.get(schemaKey);
                        if (!generatedTypeName) {
                            console.warn("Could not find generated type for ".concat(kind, " (").concat(schemaKey, ")"));
                            return "// export type ".concat(kind, " = unknown; // Could not find type for ").concat(schemaKey);
                        }
                        return "export type ".concat(kind, " = ").concat(generatedTypeName, ";");
                    }).join('\n');
                    additionalAliases = additionalTypes.map(function (_a) {
                        var name = _a.name, schemaKey = _a.schemaKey;
                        var generatedTypeName = typeNameMap.get(schemaKey);
                        if (!generatedTypeName) {
                            console.warn("Could not find generated type for ".concat(name, " (").concat(schemaKey, ")"));
                            return "// export type ".concat(name, " = unknown; // Could not find type for ").concat(schemaKey);
                        }
                        return "export type ".concat(name, " = ").concat(generatedTypeName, ";");
                    }).join('\n');
                    finalContent = "".concat(allTypes, "\n\n// Clean type aliases for main resources\n").concat(resourceAliases, "\n\n// Clean type aliases for commonly used types\n").concat(additionalAliases, "\n");
                    fs.writeFileSync(outputFile, finalContent);
                    console.log("\nGenerated ".concat(outputFile, " with ").concat(resourceSchemaKeys.length, " resource type aliases and ").concat(additionalTypes.length, " additional type aliases"));
                    return [2 /*return*/];
            }
        });
    });
}
function main() {
    return __awaiter(this, void 0, void 0, function () {
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0: return [4 /*yield*/, generateTypes('./k8sSchemas', './src/kubernetesResourceTypes/kubernetesTypes.ts', [
                        { apiVersion: 'v1', kind: 'Pod' },
                        { apiVersion: 'v1', kind: 'Service' },
                        { apiVersion: 'v1', kind: 'ConfigMap' },
                        { apiVersion: 'apps/v1', kind: 'Deployment' },
                        { apiVersion: 'apps/v1', kind: 'ReplicaSet' },
                        { apiVersion: 'apps/v1', kind: 'StatefulSet' },
                        { apiVersion: 'batch/v1', kind: 'Job' },
                        { apiVersion: 'batch/v1', kind: 'CronJob' },
                    ], [
                        // Common sub-types
                        { name: 'Container', schemaKey: 'io.k8s.api.core.v1.Container' },
                        { name: 'Volume', schemaKey: 'io.k8s.api.core.v1.Volume' },
                        { name: 'VolumeMount', schemaKey: 'io.k8s.api.core.v1.VolumeMount' },
                        { name: 'EnvVar', schemaKey: 'io.k8s.api.core.v1.EnvVar' },
                        { name: 'PersistentVolumeClaim', schemaKey: 'io.k8s.api.core.v1.PersistentVolumeClaim' },
                        { name: 'ResourceRequirements', schemaKey: 'io.k8s.api.core.v1.ResourceRequirements' },
                        { name: 'PodSpec', schemaKey: 'io.k8s.api.core.v1.PodSpec' },
                        { name: 'ObjectMeta', schemaKey: 'io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta' },
                    ])];
                case 1:
                    _a.sent();
                    return [2 /*return*/];
            }
        });
    });
}
if (require.main === module) {
    main().catch(console.error);
}
