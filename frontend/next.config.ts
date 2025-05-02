import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  /* config options here */
  reactStrictMode: true,
  transpilePackages: [
    "@cloudscape-design/components",
    "@cloudscape-design/board-components",
    "@cloudscape-design/global-styles",
    "@cloudscape-design/component-toolkit"
  ],
  webpack(config) {
    // h/t https://github.com/securingsincity/react-ace/issues/725#issuecomment-1407356137
    config.module.rules.push({
      test: /ace-builds.*\/worker-.*$/,
      type: "asset/resource",
    });
    return config;
  },
};

export default nextConfig;
