import {Construct} from "constructs";
import {StackComposer} from "../lib/stack-composer";
import {App} from "aws-cdk-lib";

export function createStackComposer(contextBlock: { [x: string]: (any); }) {
    contextBlock.stage = "unit-test"
    const app = new App({
        context: {
            contextId: "unit-test-config",
            "unit-test-config": contextBlock
        }
    })
    return new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"},
        migrationsSolutionVersion: "1.0.0"
    })
}