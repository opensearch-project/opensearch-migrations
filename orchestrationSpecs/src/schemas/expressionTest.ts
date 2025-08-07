
// Create typed parameter references
import {
    add,
    concat,
    configMap,
    greaterThan, index,
    inputParams, length, lessThan,
    literal,
    path, stepOutput, taskOutput,
    ternary,
    workflowParams
} from "@/schemas/expression";
import { z } from 'zod';
import {InputParamDef, OutputParamDef} from './parameterSchemas';
import {toArgoExpression} from "@/renderers/argoExpressionRender";

// Example types for demonstration
type User = {
    name: string;
    age: number;
    addresses: { street: string; city: string }[];
};

type Config = {
    database: {
        host: string;
        port: number;
    };
    features: string[];
};

// Define parameter schemas using your existing system
const inputParamDefs = {
    user: {
        type: z.object({
            name: z.string(),
            age: z.number(),
            addresses: z.array(z.object({
                street: z.string(),
                city: z.string()
            }))
        }),
        description: "User information"
    } satisfies InputParamDef<User, true>,

    maxAge: {
        type: z.number(),
        defaultValue: 100,
        description: "Maximum age filter",
        _hasDefault: true as const
    } satisfies InputParamDef<number, false>,

    greeting: {
        type: z.string(),
        defaultValue: "Hello",
        description: "Greeting prefix",
        _hasDefault: true as const
    } satisfies InputParamDef<string, false>
};

const outputParamDefs = {
    processedUser: {
        type: z.object({
            name: z.string(),
            category: z.string(),
            isAdult: z.boolean()
        }),
        description: "Processed user data"
    } satisfies OutputParamDef<{name: string, category: string, isAdult: boolean}>
};

const inputs = inputParams(inputParamDefs);
const workflows = workflowParams(inputParamDefs);

// Type-safe expression building with your parameter system
const examples = {
    // Simple literal
    greeting: literal("Hello, World!"),

    // Type-safe parameter references
    userFromInput: inputs.user,              // Expression<User>
    userFromWorkflow: workflows.user,        // Expression<User>
    maxAgeParam: inputs.maxAge,              // Expression<number>

    // Type-safe path access with parameters
    userName: path(inputs.user, 'name'),                    // Expression<string>
    userAge: path(inputs.user, 'age'),                      // Expression<number>
    firstAddressStreet: path(inputs.user, 'addresses[0].street'),  // Expression<string>

    // ConfigMap with type safety
    dbConfig: configMap<Config>('app-config', 'database-config'),
    dbHost: path(configMap<Config>('app-config', 'database-config'), 'database.host'),

    // String composition with parameters
    personalGreeting: concat(
        inputs.greeting,
        literal(", "),
        path(inputs.user, 'name'),
        literal("!")
    ),

    // Conditional logic with parameter comparison
    ageCategory: ternary(
        greaterThan(path(inputs.user, 'age'), literal(17)),
        literal("adult"),
        literal("minor")
    ),

    // Complex parameter-based logic
    ageCheck: ternary(
        lessThan(path(inputs.user, 'age'), inputs.maxAge),
        literal("within limit"),
        literal("exceeds limit")
    ),

    // Step and task output references
    processedFromStep: stepOutput<{name: string, category: string, isAdult: boolean}>(
        'process-user',
        'processedUser',
        outputParamDefs.processedUser
    ),

    processedFromTask: taskOutput<{name: string, category: string, isAdult: boolean}>(
        'user-processor',
        'processedUser',
        outputParamDefs.processedUser
    ),

    // Using step outputs in expressions
    processedUserName: path(
        stepOutput<{name: string, category: string, isAdult: boolean}>(
            'process-user',
            'processedUser'
        ),
        'name'
    ),

    // Array operations with parameters
    addressCount: length(path(inputs.user, 'addresses')),
    firstAddress: index(path(inputs.user, 'addresses'), literal(0)),

    // Math operations with parameters
    agePlusTen: add(path(inputs.user, 'age'), literal(10)),
    ageComparison: lessThan(path(inputs.user, 'age'), inputs.maxAge)
};

// Example of how different parameter sources generate different Argo expressions:
const argoExamples = {
    // Workflow parameter: {{workflow.parameters.user}}
    workflowUser: toArgoExpression(workflows.user),

    // Input parameter: {{inputs.parameters.user}}
    inputUser: toArgoExpression(inputs.user),

    // Step output: {{steps.process-user.outputs.parameters.processedUser}}
    stepOutput: toArgoExpression(stepOutput('process-user', 'processedUser')),

    // Task output: {{tasks.user-processor.outputs.parameters.processedUser}}
    taskOutput: toArgoExpression(taskOutput('user-processor', 'processedUser')),

    // Complex path with step output: {{steps.process-user.outputs.parameters.processedUser | jsonpath('$.name')}}
    stepOutputPath: toArgoExpression(
        path(stepOutput<{ name: string, category: string }>('process-user', 'processedUser'), 'name')
    ),

    // Concatenation with different sources:
    // Hello, {{inputs.parameters.user | jsonpath('$.name')}} from {{workflow.parameters.environment}}!
    mixedConcat: toArgoExpression(
        concat(
            literal("Hello, "),
            path(inputs.user, 'name'),
            literal(" from "),
            path(workflows.user, 'name'), // Assuming environment is also defined
            literal("!")
        )
    )
};