package mymodule

import "strings"
import "encoding/json"

#ParameterType: bool | string | int

#ParameterDetails: {
    type: "bool" | "string" | "int"
    defaultValue?: #ParameterType
    description?: string
    ...
}

#FullyProjectedTaskParameter: #ParameterDetails & {
    parameterName:  string
    envName:  string
    taskInputPath: string
}

#ExpandParamsIntoTaskParameters: {
    #in:  [string]: #ParameterDetails
    out: [...#FullyProjectedTaskParameter] & [
        for p, details in #in { 
            details & {
                parameterName:  p
                envName:  strings.ToUpper(p)
                taskInputPath: "{{inputs.parameters['\(p)']}}"
            }
        }
    ]
}

#GetArgs: {
    #in: [...#FullyProjectedTaskParameter] // env list map
    out: strings.Join([
        for i in #in 
        // for else support, see https://github.com/cue-lang/cue/issues/2122
            {
                if i.type == "bool"
                {
                """
                if [ "$\(i.envName)" = "true" ] || [ "$\(i.envName)" = "1" ]; then
                    ARGS="${ARGS} --insecureDestination"
                fi
                """   
                }
                if i.type != "bool"
                {
                    """
                    ARGS=\"${ARGS}${\(i.envName):+ --\(i.parameterName) $\(i.envName)}
                    """
                }
            }
         ], "\n")
}

#Container: {
    #parameters: [string]: #ParameterDetails
    #containerCommand: string
    #projected: (#ExpandParamsIntoTaskParameters & { #in: #parameters }).out
    // projected: #projected
    
    #args: (#GetArgs & { #in: #projected }).out
    args: #args

    env: [for p in #projected {name: p.envName, value: p.taskInputPath}]    
    _commandText:
        """
        set -e

        # Build arguments from environment variables
        ARGS=""
        \(#args)

        # Log the configuration
        echo "Starting proxy with arguments: $ARGS"

        # Execute the command
        exec \(#containerCommand) $ARGS  
        """   
    command: [
        "/bin/sh",
        "-c",
        _commandText
    ]
}

#DeployContainers: {
    name: string
    IMAGE_COMMAND=#containerCommand: string
    TASK_PARAMS=#parameters: [string]: #ParameterDetails

    #container: #Container & { 
        #parameters: TASK_PARAMS
        #containerCommand: IMAGE_COMMAND
    }
    
    _containerText: json.Marshal(#container)
    
    inputs?: {...}
    resource: {
        action: "create"
        setOwnerReference: bool
        manifest:
          """
            {
                "apiVersion": "apps/v1",
                "kind": "Deployment",
                "metadata": {
                    "generateName": "\(name)",
                    "labels": {
                        "app": "\(name)"
                    }
                },
                "spec": {
                    "replicas": "{{inputs.parameters.replicas}}",
                    "selector": {
                        "matchLabels": {
                            "app": "\(name)"
                        }
                    },
                    "template": {
                        "metadata": {
                            "labels": {
                                "app": "\(name)"
                            }
                        },
                        "spec": {
                            "containers": \(_containerText)
                        }
                    }
                }
            }
         """
    }
}

// Argo will do this substitution, not CUE - but useful for documenting what the structure will be
#captureContext: close({
    sessionName: "dummy",
    sourceConfig: #CLUSTER_CONFIG & {
        name: "firstSource"
        endpoint: "http://elasticsearch-master:9200"
        allow_insecure: true
        authConfig: {
            region: "us-east-2"
        }
    }
})




#HTTP_AUTH_BASIC: close({
    username!: string
    password!: string
})

#HTTP_AUTH_SIGV4: close({
    region!: string
    service?: string
})

#CLUSTER_CONFIG: close({
    name: string
    endpoint: string
    allow_insecure?: bool
    version?: string
    authConfig?: #HTTP_AUTH_BASIC | #HTTP_AUTH_SIGV4
})


#ProxyParameters: close({
    // backsideUriString: #ParameterDetails & {type: "string"}
    // frontsidePort: #ParameterDetails & {type: "int"}

    // image: #ParameterDetails & { type: "string", defaultValue: "migrations/migration-console:latest" }
    // initImage: #ParameterDetails & {type: "string", defaultValue: "migrations/migration-console:latest" }
    // replicas: #ParameterDetails & {type: "int", defaultValue: 1 }

    // traceDirectory: #ParameterDetails & {type: "string" }
    // noCapture: #ParameterDetails & {type: "bool" }
    // kafkaPropertiesFile: #ParameterDetails & {type: "string" }
    // kafkaClientId: #ParameterDetails & {type: "string" }
    // kafkaConnection: #ParameterDetails & {type: "string" }
    // kafkaTopic: #ParameterDetails & {type: "string" }
    // mskAuthEnabled: #ParameterDetails & {type: "bool" }
    // sslConfigFilePath: #ParameterDetails & {type: "string" }
    // maximumTrafficStreamSize: #ParameterDetails & {type: "string" }
    // allowInsecureConnectionsToBackside: #ParameterDetails & {type: "bool" }
    // numThreads: #ParameterDetails & {type: "string" }
    // destinationConnectionPoolSize: #ParameterDetails & {type: "string" }
    // destinationConnectionPoolTimeout: #ParameterDetails & {type: "string" }
    // otelCollectorEndpoint: #ParameterDetails & {type: "string", defaultValue: "http://otel-collector:4317" }
    // headerOverrides: #ParameterDetails & {type: "string" }
    suppressCaptureHeaderPairs: #ParameterDetails & {type: "string" }
})

// expanded: (#ExpandParamsIntoTaskParameters & {#in: #ProxyParameters }).out
// args: (#GetArgs & { #in: expanded }).out

(#DeployContainers & {
    #containerCommand: "/runJavaWithClasspath.sh org.opensearch.CaptureProxy"
    #parameters: #ProxyParameters

    // Task inputs - eventually move this into a Task struct
    _parametersList:  (#ExpandParamsIntoTaskParameters & {#in: #ProxyParameters }).out
    if _parametersList != [] {
        inputs: 
            parameters: 
                [for p in _parametersList {
                    {
                        name: p.parameterName
                    }
                    if p.defaultValue != _|_ { 
                        value: p.defaultValue 
                    }
                }]
    }
    name: "proxy-thing"
    resource: setOwnerReference: true
})