import {StackComposer} from "../lib/stack-composer";
import {App} from "aws-cdk-lib";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function createStackComposer(contextBlock: Record<string, any>, migrationsUserAgent?: string, region?: string): StackComposer {
    contextBlock.stage = "unit-test"
    const app = new App({
        context: {
            contextId: "unit-test-config",
            "unit-test-config": contextBlock
        }
    })
    return new StackComposer(app, {
        env: {account: "test-account", region: region ?? "us-east-1"},
        migrationsSolutionVersion: "1.0.0",
        migrationsUserAgent: migrationsUserAgent
    })
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function createStackComposerOnlyPassedContext(contextBlock: Record<string, any>) {
    const app = new App({
        context: contextBlock
    })
    return new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"},
        migrationsSolutionVersion: "1.0.0"
    })
}
