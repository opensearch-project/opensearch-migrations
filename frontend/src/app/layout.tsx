"use client";

import {
  AppLayout,
  ContentLayout,
  SideNavigation,
} from "@cloudscape-design/components";
import { useState } from "react";

const sideNav = (
  <SideNavigation
    header={{
      href: "/",
      text: "Migration Assistant",
      logo: { src: "/migrations-icon-160x160.png", alt: "" },
    }}
    items={[
      // Reference for future links
      // { type: 'link', href: '/about', text: 'About'}
      // { type: 'divider'},
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
    ]}
  ></SideNavigation>
);

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const [navigationOpen, setNavigationOpen] = useState(true);
  const toggleNavigation = () => setNavigationOpen((prev) => !prev);

  return (
    <html lang="en">
      <body>
        <AppLayout
          navigationOpen={navigationOpen}
          onNavigationChange={toggleNavigation}
          navigation={sideNav}
          toolsHide={true}
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
