"use client";

import "@cloudscape-design/global-styles/index.css";

import {
  AppLayout,
  ContentLayout,
  SideNavigation,
  SideNavigationProps,
} from "@cloudscape-design/components";
import { useState } from "react";
import "@/lib/client-config";

const defaultSession = "abc";

const navItems: SideNavigationProps.Item[] = [
  {
    type: "section-group",
    title: "Migration",
    items: [
      { type: "link", href: "/loading", text: "Loading Screen" },
      { type: "link", href: "/createSession", text: "Create Session" },
      {
        type: "link",
        href: `/viewSession?sessionName=${defaultSession}`,
        text: "View Session",
      },
      {
        type: "link",
        text: "Snapshot workflow",
        href: `/snapshot?sessionName=${defaultSession}`,
      },
      {
        type: "link",
        text: "Metadata workflow",
        href: `/metadata?sessionName=${defaultSession}`,
      },
      {
        type: "link",
        text: "Backfill workflow",
        href: `/backfill?sessionName=${defaultSession}`,
      },
    ],
  },
  {
    type: "section-group",
    title: "Tools",
    items: [
      { type: "link", text: "Transformation Playground", href: "/playground" },
      { type: "link", href: "/about", text: "About" },
    ],
  },
  {
    type: "section-group",
    title: "Help",
    items: [
      {
        type: "link",
        text: "Documentation",
        href: "https://opensearch.org/docs/latest/migration-assistant/",
        external: true,
        externalIconAriaLabel: "Opens in a new tab",
      },
      {
        type: "link",
        text: "Report an Issue",
        href: "https://github.com/opensearch-project/opensearch-migrations/issues",
        external: true,
        externalIconAriaLabel: "Opens in a new tab",
      },
      {
        type: "link",
        text: "Source Code",
        href: "https://github.com/opensearch-project/opensearch-migrations",
        external: true,
        externalIconAriaLabel: "Opens in a new tab",
      },
    ],
  },
];

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const [navigationOpen, setNavigationOpen] = useState(true);
  return (
    <html lang="en">
      <body>
        <AppLayout
          navigationOpen={navigationOpen}
          onNavigationChange={() => setNavigationOpen((p) => !p)}
          navigation={
            <SideNavigation
              header={{
                href: "/",
                text: "Migration Assistant",
                logo: { src: "/migrations-icon-160x160.png", alt: "" },
              }}
              items={navItems}
            />
          }
          toolsHide
          content={
            <ContentLayout>
              <div className="contentPlaceholder">{children}</div>
            </ContentLayout>
          }
        />
      </body>
    </html>
  );
}
