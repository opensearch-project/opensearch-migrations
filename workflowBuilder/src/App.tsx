import React from "react";
import {
  AppLayout,
  ContentLayout,
  Header,
  Link,
} from "@cloudscape-design/components";
import { ConfigurationBuilder } from "./components/ConfigurationBuilder";

export const App: React.FC = () => {
  return (
    <AppLayout
      content={
        <ContentLayout
          header={
            <Header
              variant="h1"
              description="Configure your OpenSearch migration with a visual interface. Changes are reflected in real-time in the configuration preview."
              info={
                <Link external href="https://opensearch.org/docs/latest/migration-assistant/">
                  Documentation
                </Link>
              }
            >
              Migration Configuration Builder
            </Header>
          }
        >
          <ConfigurationBuilder />
        </ContentLayout>
      }
      navigationHide
      toolsHide
      headerSelector="#header"
      footerSelector="#footer"
    />
  );
};

export default App;
