import { settings, player, toast, createStore, type SoundProfile } from "./store";
import { engine } from "./engine";
import { isNative, SonoraPlayer } from "./nativeLibrary";

export interface OutDev {
  id: string;
  label: string;
  key: string;
}

export const devices = createStore<{ outs: OutDev[]; supported: boolean; named: boolean; namesHidden: boolean }>({
  outs: [],
  supported: false,
  named: false,
  /** Android 12+ is withholding Bluetooth names until the "Nearby devices" permission is granted. */
  namesHidden: false,
});

const HEADSET = /head|ear|bud|airpod|bluetooth|bt|wh-|wf-|galaxy buds|pods|jbl|sony|bose|usb/i;
let pausedByDisconnect = false;
let prevKeys = new Set<string>();

const keyFor = (d: MediaDeviceInfo) =>
  (d.label ? d.label.replace(/\s*\(.*?\)\s*$/, "").trim() : d.deviceId.slice(0, 12)) || "default";

function publishScan(outs: OutDev[], initial: boolean, supported = true, namesHidden = false) {
  const named = outs.some((o) => o.label && o.label !== "Audio output device" && o.label !== "System default output");
  devices.set({ outs, supported, named, namesHidden });

  const keys = new Set(outs.map((o) => o.key));
  if (!initial) {
    const added = outs.filter((o) => !prevKeys.has(o.key));
    const removed = [...prevKeys].filter((k) => !keys.has(k));
    const s = settings.get();
    // On Android the platform's own "becoming noisy" broadcast pauses (handleBecomingNoisy). A device list that merely
    // changes (a second device appears, a name refreshes after the permission is granted) must never pause music.
    if (!isNative() && removed.length && player.get().playing && s.pauseOnDisconnect) {
      pausedByDisconnect = true;
      engine.pause();
      toast(`${removed[0]} disconnected — paused`);
    }
    for (const a of added) {
      const pid = s.deviceMap[a.key];
      const prof = s.profiles.find((p) => p.id === pid);
      if (prof) {
        applyProfile(prof);
        toast(`${a.label} — profile "${prof.name}" applied`);
      } else if (HEADSET.test(a.label)) {
        toast(`${a.label} connected`);
      }
      if (s.autoResume && pausedByDisconnect) {
        pausedByDisconnect = false;
        window.setTimeout(() => void engine.play(), 600);
      }
    }
  } else {
    const s = settings.get();
    const hit = outs.find((o) => s.deviceMap[o.key]);
    const prof = hit && s.profiles.find((p) => p.id === s.deviceMap[hit.key]);
    if (prof) applyProfile(prof);
  }
  player.set({
    outputLabel: outs.find((o) => o.id !== "default" && HEADSET.test(o.label))?.label || "",
  });
  prevKeys = keys;
}

async function scan(initial = false) {
  try {
    if (isNative()) {
      const result = await SonoraPlayer.getAudioOutputs();
      publishScan(result.outs, initial, result.supported, !!result.namesHidden);
      return;
    }
    if (typeof navigator === "undefined") return;
    const md = navigator.mediaDevices;
    if (!md?.enumerateDevices) return;
    const list = (await md.enumerateDevices()).filter((d) => d.kind === "audiooutput" || d.kind === "audioinput");
    const outsRaw = list.filter((d) => d.kind === "audiooutput");
    // Some browsers do not expose output endpoints; input devices are a name-only proxy.
    const src = outsRaw.length ? outsRaw : list.filter((d) => d.kind === "audioinput");
    const outs = src.map((d) => ({
      id: d.deviceId || "default",
      label: d.label || (d.deviceId === "default" ? "System default output" : "Audio output device"),
      key: keyFor(d),
    }));
    publishScan(outs, initial);
  } catch {
    if (isNative()) devices.set({ outs: [], supported: true, named: false, namesHidden: false });
  }
}

export function initDevices() {
  if (isNative()) {
    void scan(true);
    void SonoraPlayer.addListener("audioDevicesChanged", () => void scan()).catch(() => {});
    return;
  }
  if (typeof navigator === "undefined") return;
  const md = navigator.mediaDevices;
  if (!md?.enumerateDevices) return;
  void scan(true);
  md.addEventListener?.("devicechange", () => void scan());
}

/** Pause once on Android's noisy-output broadcast and let the device callback handle auto-resume. */
export function handleBecomingNoisy() {
  if (!settings.get().pauseOnDisconnect || !player.get().playing) return;
  pausedByDisconnect = true;
  engine.pause();
  toast("Audio output disconnected — paused");
}

/**
 * Android: asks for "Nearby devices" (the permission that lets Android reveal Bluetooth headset names) and rescans.
 * Never needs the microphone. If Android will not show the prompt any more, opens App info instead.
 * Web: names are unlocked through getUserMedia.
 */
export async function requestDeviceNames() {
  if (isNative()) {
    try {
      const res = await SonoraPlayer.requestHeadsetPermission();
      await scan(true);
      if (res.granted) {
        toast(devices.get().named ? "Headset names refreshed" : "Allowed. No named headset is connected right now");
      } else if (res.blocked) {
        toast("Turn on “Nearby devices” in App info → Permissions");
        await SonoraPlayer.openAppSettings().catch(() => {});
      } else {
        toast("Headset names stay generic until Nearby devices is allowed");
      }
    } catch {
      toast("Could not check the headset permission");
    }
    return;
  }
  try {
    const s = await navigator.mediaDevices.getUserMedia({ audio: true });
    s.getTracks().forEach((t) => t.stop());
    await scan(true);
    toast("Device names unlocked");
  } catch {
    toast("Permission denied — device names stay hidden");
  }
}

export function applyProfile(p: SoundProfile) {
  settings.set({
    eq: [...p.eq],
    preampDb: p.preampDb,
    bassDb: p.bassDb,
    midsDb: p.midsDb ?? 0,
    trebleDb: p.trebleDb,
    width: p.width,
    crossfeed: p.crossfeed,
    eqOn: true,
  });
}

export function saveProfile(name: string, bindDeviceKey?: string): SoundProfile {
  const s = settings.get();
  const p: SoundProfile = {
    id: Math.random().toString(36).slice(2, 9),
    name,
    eq: [...s.eq],
    preampDb: s.preampDb,
    bassDb: s.bassDb,
    midsDb: s.midsDb,
    trebleDb: s.trebleDb,
    width: s.width,
    crossfeed: s.crossfeed,
  };
  const nextProfiles = [...s.profiles, p];
  const nextMap = { ...s.deviceMap };
  if (bindDeviceKey) nextMap[bindDeviceKey] = p.id;
  settings.set({ profiles: nextProfiles, deviceMap: nextMap });
  return p;
}
