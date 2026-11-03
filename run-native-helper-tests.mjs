// Compiles and runs the Android-free Java helpers on a plain JVM (needs a JDK).
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const src = fs.existsSync("native/android/java/app/sonora/player/SonoraUtil.java")
  ? "native/android/java/app/sonora/player"
  : ".";
const out = fs.mkdtempSync(path.join(os.tmpdir(), "sonora-java-"));
const files = [`${src}/SonoraUtil.java`, `${src}/SonoraUtilTest.java`];

function compile() {
  try {
    execFileSync("javac", ["-d", out, ...files], { stdio: "inherit" });
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    execFileSync("java", ["-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", out, ...files], { stdio: "inherit" });
  }
}

compile();
execFileSync("java", ["-cp", out, "app.sonora.player.SonoraUtilTest"], { stdio: "inherit" });
