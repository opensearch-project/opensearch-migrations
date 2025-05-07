"use client";

import ContentLayout from "@cloudscape-design/components/content-layout";
import Header from "@cloudscape-design/components/header";
import SpaceBetween from "@cloudscape-design/components/space-between";
import { PlaygroundProvider } from "@/context/PlaygroundProvider";
import { Container, Grid } from "@cloudscape-design/components";
import InputDocumentSection from "@/components/playground/InputDocumentSection";
import OutputDocumentSection from "@/components/playground/OutputDocumentSection";
import TransformationSection from "@/components/playground/TransformationSection";

export default function Home() {
  return (
    <ContentLayout
      defaultPadding
      header={
        <SpaceBetween size="m">
          <Header variant="h1">Transformation Playground</Header>
        </SpaceBetween>
      }
    >
      <Container>
        <PlaygroundProvider>
          <Grid
            gridDefinition={[
              { colspan: { default: 12, m: 3 } },
              { colspan: { default: 12, m: 6 } },
              { colspan: { default: 12, m: 3 } },
            ]}
          >
            <InputDocumentSection />
            <TransformationSection />
            <OutputDocumentSection />
          </Grid>
        </PlaygroundProvider>
      </Container>
    </ContentLayout>
  );
}
