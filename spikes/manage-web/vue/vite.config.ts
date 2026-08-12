import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    host: "127.0.0.1",
    port: 4400,
  },
  test: {
    environment: "jsdom",
    css: true,
  },
});
