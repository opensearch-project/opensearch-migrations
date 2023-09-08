import {Construct} from "constructs";
import {StackComposer} from "../lib/stack-composer";

export function createStackComposer(app: Construct) {
    return new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"}, stage: "unittest", copilotAppName: "test-app", copilotEnvName: "unittest"
    })
}