import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  root: "src/renderer",
  base: "./",
  plugins: [react()],
  publicDir: "public",
  build: {
    outDir: "../../dist/renderer",
    emptyOutDir: true,
    target: "chrome138",
    chunkSizeWarningLimit: 1200,
  },
});
