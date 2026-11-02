import { useEffect, useRef } from "react";
import { pushBack } from "./back";

/** While `active`, Android Back calls `onBack` (newest overlay first) instead of leaving the screen. */
export function useBackHandler(active: boolean, onBack: () => void) {
  const ref = useRef(onBack);
  ref.current = onBack;
  useEffect(() => {
    if (!active) return;
    return pushBack(() => ref.current());
  }, [active]);
}
