// Tiny IndexedDB key/value wrapper (lyrics edits, loudness cache, liked songs, folder handle)
let dbp: Promise<IDBDatabase> | null = null;

function db() {
  if (!dbp)
    dbp = new Promise((res, rej) => {
      const r = indexedDB.open("sonora-player", 1);
      r.onupgradeneeded = () => r.result.createObjectStore("kv");
      r.onsuccess = () => res(r.result);
      r.onerror = () => rej(r.error);
    });
  return dbp;
}

export async function kvGet<T = unknown>(key: string): Promise<T | undefined> {
  try {
    if (typeof indexedDB === "undefined") return undefined;
    const d = await db();
    return await new Promise((res) => {
      const q = d.transaction("kv").objectStore("kv").get(key);
      q.onsuccess = () => res(q.result as T);
      q.onerror = () => res(undefined);
    });
  } catch {
    return undefined;
  }
}

export async function kvSet(key: string, val: unknown) {
  try {
    if (typeof indexedDB === "undefined") return;
    const d = await db();
    await new Promise<void>((res) => {
      const tx = d.transaction("kv", "readwrite");
      tx.objectStore("kv").put(val, key);
      tx.oncomplete = () => res();
      tx.onerror = () => res();
    });
  } catch {}
}
