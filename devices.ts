import { settings, player, toast, createStore, type SoundProfile } from "./store";
import { engine } from "./engine";

export interface OutDev {
  id: string;
  label: string;
  key: string;
}

export const devices = createStore<{ outs: OutDev[]; supported: boolean; named: boolean }>({
  outs: [],
  supported: false,
  named: false,
});

const HEADSET = /head|ear|bud|airpod|bluetooth|bt|wh-|wf-|galaxy buds|pods|jbl|sony|bose|usb/i;
let pausedByDisconnect = false;
let prevKeys = new Set<string>();

const keyFor = (d: MediaDeviceInfo) =>
  (d.label ? d.label.replace(/\s*\(.*?\)\s*$/, "").trim() : d.deviceId.slice(0, 12)) || "default";

async function scan(initial = false) {
  if (typeof navigator === "undefined") return;
  const md = navigator.mediaDevices;
  if (!md?.enumerateDevices) return;
  try {
    const list = (await md.enumerateDevices()).filter((d) => d.kind === "audiooutput" || d.kind === "audioinput");
    const outsRaw = list.filter((d) => d.kind === "audiooutput");
    // On Android WebView outputs often aren't listed; headset mics are a good proxy
    const src = outsRaw.length ? outsRaw : list.filter((d) => d.kind === "audioinput");
    const outs = src.map((d) => ({
      id: d.deviceId || "default",
      label: d.label || (d.deviceId === "default" ? "System default output" : "Audio output device"),
      key: keyFor(d),
    }));
    const named = outs.some((o) => o.label && o.label !== "Audio output device" && o.label !== "System default output");
    devices.set({ outs, supported: true, named });

    const keys = new Set(outs.map((o) => o.key));
    if (!initial) {
      const added = outs.filter((o) => !prevKeys.has(o.key));
      const removed = [...prevKeys].filter((k) => !keys.has(k));
      const s = settings.get();
      if (removed.length && player.get().playing && s.pauseOnDisconnect) {
        engine.pause();
        pausedByDisconnect = true;
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
          setTimeout(() => engine.play(), 600);
        }
      }
    } else {
      const s = settings.get();
      const hit = outs.find((o) => s.deviceMap[o.key] && o.id !== "default" && o.id !== "communications");
      const prof = hit && s.profiles.find((p) => p.id === s.deviceMap[hit.key]);
      if (prof) applyProfile(prof);
    }
    player.set({ outputLabel: outs.find((o) => o.id !== "default" && HEADSET.test(o.label))?.label || "" });
    prevKeys = keys;
  } catch {}
}

export function initDevices() {
  if (typeof navigator === "undefined") return;
  const md = navigator.mediaDevices;
  if (!md?.enumerateDevices) return;
  scan(true);
  md.addEventListener?.("devicechange", () => scan());
}

/**
 * Only requests audio permission when the user explicitly clicks "Unlock device names"
 */
export async function requestDeviceNames() {
  try {
    const s = await navigator.mediaDevices.getUserMedia({ audio: true });
    s.getTracks().forEach((t) => t.stop());
    await scan();
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
