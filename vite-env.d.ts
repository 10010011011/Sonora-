/// <reference types="vite/client" />

interface Window {
  showDirectoryPicker?: (opts?: { mode?: string; id?: string }) => Promise<FileSystemDirectoryHandle>;
}

interface FileSystemDirectoryHandle {
  name: string;
  values(): AsyncIterable<FileSystemHandle & { kind: "file" | "directory"; name: string; getFile?: () => Promise<File> }>;
  queryPermission?(o: { mode: string }): Promise<PermissionState>;
  requestPermission?(o: { mode: string }): Promise<PermissionState>;
}

interface HTMLMediaElement {
  preservesPitch: boolean;
  setSinkId?: (id: string) => Promise<void>;
}

interface AudioContext {
  setSinkId?: (id: string) => Promise<void>;
}
