import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import fs from "node:fs";
import path from "node:path";
import { defineConfig } from "vite";

const staticAssets = [
  "favicon.svg",
  "dm-sans-latin.woff2",
  "manrope-latin.woff2"
];

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    {
      name: "copy-flat-static-assets",
      apply: "build",
      writeBundle({ dir }) {
        const outDir = path.resolve(dir || "dist");
        fs.mkdirSync(outDir, { recursive: true });
        for (const file of staticAssets) {
          if (fs.existsSync(file)) {
            fs.copyFileSync(file, path.join(outDir, file));
          }
        }
      }
    }
  ]
});
