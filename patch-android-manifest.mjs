// Idempotent AndroidManifest.xml patcher for the generated Capacitor project.
// Usage: node scripts/patch-android-manifest.mjs android/app/src/main/AndroidManifest.xml
import fs from "node:fs";

const file = process.argv[2];
if (!file || !fs.existsSync(file)) {
  console.error("AndroidManifest.xml not found: " + file);
  process.exit(1);
}
let xml = fs.readFileSync(file, "utf8");

// Permissions we no longer want. The SAF picker and in-app storage need none of the legacy storage
// permissions, and Bluetooth "becoming noisy" events are delivered without BLUETOOTH_CONNECT.
const REMOVE = ["READ_MEDIA_AUDIO", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MODIFY_AUDIO_SETTINGS", "BLUETOOTH_CONNECT"];
for (const p of REMOVE) {
  xml = xml.replace(new RegExp(`[ \\t]*<uses-permission[^>]*android\\.permission\\.${p}"[^>]*/>\\s*\\n?`, "g"), "");
}

// Permissions the media service needs.
const ADD = ["FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK", "POST_NOTIFICATIONS", "WAKE_LOCK"];
const missing = ADD.filter((p) => !xml.includes(`android.permission.${p}"`));
if (missing.length) {
  const lines = missing.map((p) => `    <uses-permission android:name="android.permission.${p}" />`).join("\n");
  xml = xml.replace(/<\/manifest>/, `${lines}\n</manifest>`);
}

// Foreground media-playback service.
if (!xml.includes('android:name=".PlaybackService"')) {
  const service =
    '        <service\n' +
    '            android:name=".PlaybackService"\n' +
    '            android:exported="false"\n' +
    '            android:foregroundServiceType="mediaPlayback" />\n';
  xml = xml.replace(/<\/application>/, `${service}    </application>`);
}

// Predictive back (required behaviour on Android 15+ when targeting API 35+).
if (!xml.includes("android:enableOnBackInvokedCallback")) {
  xml = xml.replace(/<application\b/, '<application android:enableOnBackInvokedCallback="true"');
}


// "Open with Sonora" for audio files (file managers, downloads, messaging apps).
if (!xml.includes("sonora-open-with")) {
  const filter =
    "            <!-- sonora-open-with -->\n" +
    "            <intent-filter>\n" +
    '                <action android:name="android.intent.action.VIEW" />\n' +
    '                <category android:name="android.intent.category.DEFAULT" />\n' +
    '                <category android:name="android.intent.category.BROWSABLE" />\n' +
    '                <data android:scheme="content" />\n' +
    '                <data android:scheme="file" />\n' +
    '                <data android:mimeType="audio/*" />\n' +
    '                <data android:mimeType="application/ogg" />\n' +
    "            </intent-filter>\n";
  const activity = /(<activity\b[^>]*\.MainActivity[^>]*>[\s\S]*?)(<\/activity>)/;
  if (!activity.test(xml)) {
    console.error("MainActivity <activity> element not found; open-with intent filter not added");
    process.exit(1);
  }
  xml = xml.replace(activity, `$1${filter}        $2`);
}

// Theme launcher icons. The default alias takes over the MAIN/LAUNCHER filter, so nothing changes until the user opts in.
if (!xml.includes("sonora-icon-aliases")) {
  const launcher = /[ \t]*<intent-filter>\s*<action android:name="android\.intent\.action\.MAIN"\s*\/>\s*<category android:name="android\.intent\.category\.LAUNCHER"\s*\/>\s*<\/intent-filter>[ \t]*\n?/;
  if (launcher.test(xml)) {
    xml = xml.replace(launcher, "");
    const alias = (id, name, enabled) =>
      `        <activity-alias\n` +
      `            android:name=".Icon${name}"\n` +
      `            android:targetActivity=".MainActivity"\n` +
      `            android:enabled="${enabled}"\n` +
      `            android:exported="true"\n` +
      `            android:label="@string/app_name"\n` +
      `            android:icon="@mipmap/ic_launcher${id ? "_" + id : ""}"\n` +
      `            android:roundIcon="@mipmap/ic_launcher${id ? "_" + id : ""}${id ? "" : "_round"}">\n` +
      `            <intent-filter>\n` +
      `                <action android:name="android.intent.action.MAIN" />\n` +
      `                <category android:name="android.intent.category.LAUNCHER" />\n` +
      `            </intent-filter>\n` +
      `        </activity-alias>\n`;
    const aliases =
      "        <!-- sonora-icon-aliases -->\n" +
      alias("", "Default", true) +
      alias("vinyl", "Vinyl", false) +
      alias("miku", "Miku", false) +
      alias("teto", "Teto", false) +
      alias("neru", "Neru", false);
    xml = xml.replace(/<\/application>/, `${aliases}    </application>`);
  } else {
    console.warn("MAIN/LAUNCHER filter not found; theme icon aliases skipped");
  }
}

fs.writeFileSync(file, xml);
console.log("Patched " + file);
