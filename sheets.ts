import { createStore } from "./store";

/** Which overlay is open. One store so any screen (Library, Home, Now playing, the player bar) can open them. */
export interface SheetState {
  /** Track menu: library index of the track, and the playlist it is being shown from (if any). */
  menu: { idx: number; playlistId?: string } | null;
  /** Tag editor for one track id. */
  tags: string | null;
  /** "Add to playlist" picker for these track ids. */
  picker: string[] | null;
  /** "Add songs" picker for one playlist (by playlist id): pick any library songs to put into it. */
  songPicker: string | null;
  queue: boolean;
  bookmarks: boolean;
}

const closed: SheetState = { menu: null, tags: null, picker: null, songPicker: null, queue: false, bookmarks: false };
export const sheets = createStore<SheetState>(closed);

export const openTrackMenu = (idx: number, playlistId?: string) => sheets.set({ menu: { idx, playlistId } });
export const openTagEditor = (id: string) => sheets.set({ ...closed, tags: id });
export const openPlaylistPicker = (ids: string[]) => sheets.set({ ...closed, picker: ids });
export const openSongPicker = (playlistId: string) => sheets.set({ ...closed, songPicker: playlistId });
export const openQueue = () => sheets.set({ ...closed, queue: true });
export const openBookmarks = () => sheets.set({ ...closed, bookmarks: true });
export const closeSheets = () => sheets.set(closed);

/* In-app dialogs. window.prompt / window.confirm are not reliable inside the Android WebView, so they are not used. */
export interface DialogState {
  open: boolean;
  id?: number;
  title?: string;
  message?: string;
  /** Set for a text dialog; undefined for a confirm dialog. */
  value?: string;
  okLabel?: string;
  onOk?: (value: string) => void;
}
export const dialog = createStore<DialogState>({ open: false });
let dialogSeq = 0;
export const askText = (title: string, value: string, onOk: (v: string) => void, okLabel = "Save") =>
  dialog.set({ open: true, id: ++dialogSeq, title, message: undefined, value, okLabel, onOk });
export const askConfirm = (title: string, message: string, onOk: () => void, okLabel = "Delete") =>
  dialog.set({ open: true, id: ++dialogSeq, title, message, value: undefined, okLabel, onOk: () => onOk() });
export const closeDialog = () => dialog.set({ open: false });
