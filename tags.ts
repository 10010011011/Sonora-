// Minimal, fast metadata reader: ID3v2.3/2.4 (mp3) + FLAC Vorbis comments.
// Only reads the header region of the file — never the whole audio stream.

export interface Tags {
  title?: string;
  artist?: string;
  album?: string;
  cover?: Blob;
  rgTrack?: number;
  rgAlbum?: number;
  lyrics?: string;
}

const MAX_HEAD = 4 * 1024 * 1024;

function decodeText(b: Uint8Array, enc: number): string {
  try {
    let label = "latin1";
    if (enc === 1) label = "utf-16";
    else if (enc === 2) label = "utf-16be";
    else if (enc === 3) label = "utf-8";
    return new TextDecoder(label).decode(b).replace(/\0+$/g, "").replace(/\0/g, " / ").trim();
  } catch {
    return "";
  }
}

function findTerm(b: Uint8Array, start: number, enc: number) {
  const wide = enc === 1 || enc === 2;
  for (let i = start; i < b.length - (wide ? 1 : 0); i += wide ? 2 : 1) {
    if (b[i] === 0 && (!wide || b[i + 1] === 0)) return i;
  }
  return b.length;
}

const parseGain = (s?: string) => {
  if (!s) return undefined;
  const v = parseFloat(s);
  return isFinite(v) ? v : undefined;
};

async function readID3(file: Blob): Promise<Tags | null> {
  const head = new Uint8Array(await file.slice(0, 10).arrayBuffer());
  if (head[0] !== 0x49 || head[1] !== 0x44 || head[2] !== 0x33) return null;
  const ver = head[3];
  if (ver < 3) return null;
  const size = ((head[6] & 0x7f) << 21) | ((head[7] & 0x7f) << 14) | ((head[8] & 0x7f) << 7) | (head[9] & 0x7f);
  const buf = new Uint8Array(await file.slice(10, 10 + Math.min(size, MAX_HEAD)).arrayBuffer());
  const dv = new DataView(buf.buffer);
  const t: Tags = {};
  let p = 0;
  if (head[5] & 0x40) {
    const es = dv.getUint32(0);
    p = ver === 4 ? ((es & 0x7f000000) >> 3) | ((es & 0x7f0000) >> 2) | ((es & 0x7f00) >> 1) | (es & 0x7f) : es + 4;
  }
  while (p + 10 < buf.length) {
    const id = String.fromCharCode(buf[p], buf[p + 1], buf[p + 2], buf[p + 3]);
    if (!/^[A-Z0-9]{4}$/.test(id)) break;
    let fs = dv.getUint32(p + 4);
    if (ver === 4) fs = ((fs & 0x7f000000) >> 3) | ((fs & 0x7f0000) >> 2) | ((fs & 0x7f00) >> 1) | (fs & 0x7f);
    const body = buf.subarray(p + 10, p + 10 + fs);
    p += 10 + fs;
    if (!body.length) continue;
    const enc = body[0];
    if (id === "TIT2") t.title = decodeText(body.subarray(1), enc);
    else if (id === "TPE1") t.artist = decodeText(body.subarray(1), enc);
    else if (id === "TALB") t.album = decodeText(body.subarray(1), enc);
    else if (id === "TXXX") {
      const e = findTerm(body, 1, enc);
      const desc = decodeText(body.subarray(1, e), enc).toUpperCase();
      const val = decodeText(body.subarray(e + (enc === 1 || enc === 2 ? 2 : 1)), enc);
      if (desc === "REPLAYGAIN_TRACK_GAIN") t.rgTrack = parseGain(val);
      if (desc === "REPLAYGAIN_ALBUM_GAIN") t.rgAlbum = parseGain(val);
      if (desc === "LYRICS" && !t.lyrics) t.lyrics = val;
    } else if (id === "USLT") {
      const e = findTerm(body, 4, enc);
      t.lyrics = decodeText(body.subarray(e + (enc === 1 || enc === 2 ? 2 : 1)), enc);
    } else if (id === "APIC" && !t.cover) {
      const me = body.indexOf(0, 1);
      const mime = new TextDecoder("latin1").decode(body.subarray(1, me)) || "image/jpeg";
      const de = findTerm(body, me + 2, enc);
      const start = de + (enc === 1 || enc === 2 ? 2 : 1);
      t.cover = new Blob([body.slice(start)], { type: mime.includes("/") ? mime : "image/" + mime.toLowerCase() });
    }
  }
  return t;
}

async function readFLAC(file: Blob): Promise<Tags | null> {
  const buf = new Uint8Array(await file.slice(0, MAX_HEAD).arrayBuffer());
  if (buf[0] !== 0x66 || buf[1] !== 0x4c || buf[2] !== 0x61 || buf[3] !== 0x43) return null;
  const dv = new DataView(buf.buffer);
  const t: Tags = {};
  let p = 4;
  const td = new TextDecoder();
  while (p + 4 <= buf.length) {
    const hdr = buf[p];
    const last = hdr & 0x80;
    const type = hdr & 0x7f;
    const len = (buf[p + 1] << 16) | (buf[p + 2] << 8) | buf[p + 3];
    const s = p + 4;
    if (s + len > buf.length) break;
    if (type === 4) {
      let q = s;
      const vl = dv.getUint32(q, true);
      q += 4 + vl;
      const n = dv.getUint32(q, true);
      q += 4;
      for (let i = 0; i < n && q < s + len; i++) {
        const l = dv.getUint32(q, true);
        q += 4;
        const kv = td.decode(buf.subarray(q, q + l));
        q += l;
        const eq = kv.indexOf("=");
        const k = kv.slice(0, eq).toUpperCase();
        const v = kv.slice(eq + 1);
        if (k === "TITLE") t.title = v;
        else if (k === "ARTIST") t.artist = v;
        else if (k === "ALBUM") t.album = v;
        else if (k === "REPLAYGAIN_TRACK_GAIN") t.rgTrack = parseGain(v);
        else if (k === "REPLAYGAIN_ALBUM_GAIN") t.rgAlbum = parseGain(v);
        else if (k === "LYRICS" || k === "UNSYNCEDLYRICS") t.lyrics = v;
      }
    } else if (type === 6 && !t.cover) {
      let q = s + 4;
      const ml = dv.getUint32(q);
      q += 4;
      const mime = td.decode(buf.subarray(q, q + ml));
      q += ml;
      const dl = dv.getUint32(q);
      q += 4 + dl + 16;
      const pl = dv.getUint32(q);
      q += 4;
      t.cover = new Blob([buf.slice(q, q + pl)], { type: mime || "image/jpeg" });
    }
    p = s + len;
    if (last) break;
  }
  return t;
}

export async function readTags(file: Blob): Promise<Tags> {
  try {
    return (await readID3(file)) || (await readFLAC(file)) || {};
  } catch {
    return {};
  }
}

export function titleFromName(name: string) {
  const base = name.replace(/\.[^.]+$/, "").replace(/_/g, " ");
  const m = base.match(/^(.+?)\s+-\s+(.+)$/);
  if (m) return { artist: m[1].replace(/^\d+[.\s-]*/, "").trim(), title: m[2].trim() };
  return { artist: "", title: base.replace(/^\d+[.\s-]+/, "").trim() || base };
}

/**
 * Splits a track's artist field and any "(feat. X)" / "(ft. X)" credits in the title
 * into individual artist names so featured and collaborating artists appear in the Artists tab.
 */
export function extractArtists(artist: string, title = ""): string[] {
  const raw: string[] = [];
  if (artist && artist.trim()) {
    raw.push(...artist.split(/\s*(?:\/|;|,|\s+&\s+|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*/i));
  }
  const featMatch = title.match(/[([]?\b(?:feat\.?|ft\.?)\s+([^)\]]+)[)\]]?/i);
  if (featMatch?.[1]) {
    raw.push(...featMatch[1].split(/\s*(?:\/|;|,|\s+&\s+)\s*/i));
  }
  const seen = new Set<string>();
  const out: string[] = [];
  for (const item of raw) {
    const clean = item.trim();
    if (!clean) continue;
    const key = clean.toLowerCase();
    if (!seen.has(key)) {
      seen.add(key);
      out.push(clean);
    }
  }
  return out.length ? out : [artist?.trim() || "Unknown artist"];
}
