// Idempotent AndroidManifest.xml patcher for the generated Capacitor project.
// Usage: node scripts/patch-android-manifest.mjs android/app/src/main/AndroidManifest.xml
import fs from "node:fs";

const file = process.argv[2];
if (!file || !fs.existsSync(file)) {
  console.error("AndroidManifest.xml not found: " + file);
  process.exit(1);
}
let xml = fs.readFileSync(file, "utf8");

// Permissions we no longer want.
const REMOVE = ["READ_MEDIA_AUDIO", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MODIFY_AUDIO_SETTINGS", "BLUETOOTH_CONNECT"];
for (const p of REMOVE) {
  xml = xml.replace(new RegExp('[ \\t]*<uses-permission[^>]*android\\.permission\\.' + p + '"[^>]*/>\\s*\\n?', "g"), "");
}

// Permissions the media service needs.
const ADD = ["FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK", "POST_NOTIFICATIONS", "WAKE_LOCK"];
const missing = ADD.filter((p) => !xml.includes("android.permission." + p + '"'));
if (missing.length) {
  const lines = missing.map((p) => '    <uses-permission android:name="android.permission.' + p + '" />').join("\n");
  xml = xml.replace(/<\/manifest>/, lines + "\n</manifest>");
}

// Foreground media-playback service.
if (!xml.includes('android:name=".PlaybackService"')) {
  const service =
    '        <service\n' +
    '            android:name=".PlaybackService"\n' +
    '            android:exported="false"\n' +
    '            android:foregroundServiceType="mediaPlayback" />\n';
  xml = xml.replace(/<\/application>/, service + '    </application>');
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
  xml = xml.replace(activity, "$1" + filter + "        $2");
}

// Replace any previous icon-alias block so upgrades and clean builds get the same choices.
const marker = "<!-- sonora-icon-aliases -->";
const markerAt = xml.indexOf(marker);
if (markerAt !== -1) {
  const blockStart = xml.lastIndexOf("\n", markerAt);
  const applicationEnd = xml.indexOf("</application>", markerAt);
  if (applicationEnd !== -1) xml = xml.slice(0, blockStart === -1 ? markerAt : blockStart + 1) + xml.slice(applicationEnd);
}

// Remove Capacitor's original launcher filter (it belongs to the default alias now).
const launcher = /[ \t]*<intent-filter>\s*<action android:name="android\.intent\.action\.MAIN"\s*\/>\s*<category android:name="android\.intent\.category\.LAUNCHER"\s*\/>\s*<\/intent-filter>[ \t]*\n?/;
xml = xml.replace(launcher, "");

const alias = (name, resource, enabled) => {
  const icon = resource ? "@mipmap/ic_launcher_" + resource : "@mipmap/ic_launcher";
  const roundIcon = resource ? icon : "@mipmap/ic_launcher_round";
  return '        <activity-alias\n' +
    '            android:name=".Icon' + name + '"\n' +
    '            android:targetActivity=".MainActivity"\n' +
    '            android:enabled="' + enabled + '"\n' +
    '            android:exported="true"\n' +
    '            android:label="@string/app_name"\n' +
    '            android:icon="' + icon + '"\n' +
    '            android:roundIcon="' + roundIcon + '">\n' +
    '            <intent-filter>\n' +
    '                <action android:name="android.intent.action.MAIN" />\n' +
    '                <category android:name="android.intent.category.LAUNCHER" />\n' +
    '            </intent-filter>\n' +
    '        </activity-alias>\n';
};
const aliases =
  "        <!-- sonora-icon-aliases -->\n" +
  alias("Default", "", true) +
  alias("General", "general", false) +
  alias("Miku", "miku", false) +
  alias("Teto", "teto", false) +
  alias("Neru", "neru", false) +
  // Legacy component retained so the native bridge can disable it on upgrades.
  alias("Vinyl", "general", false);
xml = xml.replace(/<\/application>/, aliases + "    </application>");

fs.writeFileSync(file, xml);
console.log("Patched " + file);
