import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

const cloudscape = fileURLToPath(new URL("./cloudscape.html", import.meta.url));
const reactAria = fileURLToPath(new URL("./react-aria.html", import.meta.url));

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      input: {
        cloudscape,
        "react-aria": reactAria,
      },
    },
  },
  server: {
    host: "127.0.0.1",
    port: 4180,
  },
});
