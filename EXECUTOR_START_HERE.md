# EXECUTOR START HERE (read this at the start of EVERY session)

You are continuing the native Kotlin rewrite of Sonora. The plan is in PLAN (the "Sonora Native - Master Plan" document, added to the repo as PLAN.md/.docx/.pdf).
The old WebView app source is still in this repo root (*.tsx, *.ts, index.css, *.java) and is the SPECIFICATION for looks and behaviour.

## Rules that never change
1. Do ONE task card per session, exactly as written in PLAN. Do not start the next task, do not "improve" things, do not refactor.
2. Do not change versions in libs.versions.toml, do not upgrade AGP/Gradle/Kotlin/Compose/Media3. If a build fails because of a version, follow PLAN > Toolchain ladder.
3. You cannot compile here. NEVER say "the build passes". After you deliver files, the user runs the GitHub Action and pastes the "Build error digest" back. Only then fix errors.
4. Deliver ONLY the changed/new files, flat (no folders), using the naming rules in assemble_native.sh. Never re-send unchanged files.
5. Look/behaviour = the old app. Read the exact old source lines named in the task card and the reference screenshots in the user's message. Copy numbers (dp, colors, durations) from index.css/TSX; do not guess.
6. Keep every JSON field name in Models.kt identical to the old app (data contract). Never delete legacy data fields.
7. Anything you cannot do exactly: STOP, write it as a Deviation (D-xx) in PROGRESS.md and tell the user. Do not silently substitute.

## Session start checklist
- Read PROGRESS.md (what is done, what is next, open deviations).
- Read the task card you were given in PLAN, plus the "Pitfalls" section.
- Ask the user for the reference screenshots only if the task is UI and none were attached.

## Session end checklist
- Output the changed files (flat) + an updated PROGRESS.md (mark the task "built, awaiting CI").
- Give the user the on-device test script for this task (from the task card) in 5 lines or fewer.

## Build loop
User pushes to branch `native` -> Actions "Build Native APK" -> if red, user pastes the "Build error digest" (job summary). Fix ONLY the listed errors; re-deliver only the files you touched.
After 3 red builds on the same task: stop and use the task's Fallback (in PLAN) or escalate to a stronger model with the digest + the files.
