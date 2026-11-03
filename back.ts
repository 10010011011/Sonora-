// Back-button stack. Screens and overlays register a closer while they are open; the native Back
// handler pops the newest one. When the stack is empty, Android moves the app to the background
// (music keeps playing) instead of destroying the WebView.

type Closer = () => void;

const stack: Closer[] = [];
let onChange: (hasHandlers: boolean) => void = () => {};

/** Called by the native bridge so Java knows whether JS currently wants the Back event. */
export function setBackSync(fn: (hasHandlers: boolean) => void) {
  onChange = fn;
  fn(stack.length > 0);
}

export function pushBack(closer: Closer): () => void {
  stack.push(closer);
  onChange(true);
  return () => {
    const i = stack.lastIndexOf(closer);
    if (i >= 0) stack.splice(i, 1);
    onChange(stack.length > 0);
  };
}

/** @returns true if something consumed the event. */
export function handleBack(): boolean {
  const closer = stack.pop();
  if (!closer) return false;
  closer();
  onChange(stack.length > 0);
  return true;
}

export function backDepth() {
  return stack.length;
}
