"use client";

import { useState, useEffect } from "react";
import FormField from "@cloudscape-design/components/form-field";
import Input from "@cloudscape-design/components/input";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import RadioGroup from "@cloudscape-design/components/radio-group";
import { useSourceCluster } from "../session/apiHooks";
import { BasicAuth, SigV4Auth } from "@/generated/api";
import { Alert, Checkbox, StatusIndicator } from "@cloudscape-design/components";

type AuthType = "NO_AUTH" | "BASIC_AUTH" | "SIGV4";

interface SourceConfigureProps {
  readonly sessionName: string;
}

export default function SourceConfigure({ sessionName }: SourceConfigureProps) {
  const { isLoading, data: cluster, error } = useSourceCluster(sessionName);
  const [selectedAuthType, setSelectedAuthType] = useState<AuthType | null>(null);
  const [showVersionOverride, setShowVersionOverride] = useState<boolean>(false);
  
  // Update the selected auth type and version override when the cluster data is loaded
  useEffect(() => {
    if (cluster?.auth?.type) {
      setSelectedAuthType(cluster.auth.type.toUpperCase() as AuthType);
    }
    
      setShowVersionOverride(!!cluster?.version_override);
  }, [cluster]);
  
  // Debounce function to prevent too many API calls
  const debounce = (func: Function, delay: number) => {
    let timer: NodeJS.Timeout;
    return (...args: any[]) => {
      clearTimeout(timer);
      timer = setTimeout(() => func(...args), delay);
    };
  };

  if (isLoading) {
    return (
      <Box padding="xl">
        <StatusIndicator type="loading">Loading source cluster…</StatusIndicator>
      </Box>
    );
  }

  if (error) {
    return (
      <Alert type="error" header="Failed to load source cluster">
        {String((error as any)?.message ?? error)}
      </Alert>
    );
  }

  return (
      <SpaceBetween size="l">
        <FormField label="Source Endpoint" description="Enter the endpoint URL for your source cluster">
          <Input 
            placeholder="https://source-cluster-endpoint:9200"
            value={cluster?.endpoint || ""}
            onChange={() => {}}
          />
        </FormField>
        
        <FormField label="Authentication Type" description="Select the authentication method for your source cluster">
          <RadioGroup
            items={[
              { value: "NO_AUTH", label: "No Authentication" },
              { value: "BASIC_AUTH", label: "Basic Authentication" },
              { value: "SIGV4", label: "AWS SigV4" }
            ] satisfies { value: AuthType; label: string }[]}
            value={selectedAuthType}
            onChange={({ detail }) => setSelectedAuthType(detail.value as AuthType)}
          />
        </FormField>
        
        {selectedAuthType === "BASIC_AUTH" && (
          <SpaceBetween size="m">
            <FormField label="Username" description="Enter the username for Basic Authentication">
              <Input 
                placeholder="Username"
                value={(cluster?.auth as BasicAuth)?.username || ""}
                onChange={() => {}}
              />
            </FormField>
            
            <FormField label="Password" description="Enter the password for Basic Authentication">
              <Input 
                placeholder="Password"
                type="password"
                value="********"
                onChange={() => {}}
              />
            </FormField>
          </SpaceBetween>
        )}
        
        {selectedAuthType === "SIGV4" && (
          <SpaceBetween size="m">
            <FormField label="Region" description="AWS Region for SigV4 signing">
              <Input 
                placeholder="us-east-1"
                value={(cluster?.auth as SigV4Auth)?.region || ""}
                onChange={() => {}}
              />
            </FormField>
            
            <FormField label="Service" description="AWS Service name for SigV4 signing">
              <Input 
                placeholder="es"
                value={(cluster?.auth as SigV4Auth)?.service || ""}
                onChange={() => {}}
              />
            </FormField>
          </SpaceBetween>
        )}
        
        <FormField>
          <Checkbox
            checked={showVersionOverride}
            onChange={({ detail }) => {
              setShowVersionOverride(detail.checked);
            }}
          >
            Override cluster version {cluster?.version_override}
          </Checkbox>
        </FormField>
        
        {showVersionOverride && (
          <SpaceBetween size="m">
                <FormField label="Version override" description="The version override for source cluster.">
                  <Input 
                    placeholder="OpenSearch 2.11.1"
                    value={cluster?.version_override}
                    onChange={() => {}}
                  />
                </FormField>
          </SpaceBetween>
        )}
      </SpaceBetween>
  );
};
