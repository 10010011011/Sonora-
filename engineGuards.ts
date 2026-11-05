export type SkipAction = "next" | "prev";

/** True only for the same transport action repeated inside the debounce window. */
export function isDuplicateSkip(
  action: SkipAction,
  lastAction: SkipAction | "",
  lastAt: number,
  now: number,
  windowMs = 350,
): boolean {
  const elapsed = now - lastAt;
  return action === lastAction && elapsed >= 0 && elapsed < windowMs;
}

/**
 * A queue position alone is not a stable identity: a queue edit can replace the
 * track at the same position while the other audio deck is still preloaded.
 */
export function matchesExpectedPreloadedDeck(
  loadedPosition: number | null,
  expectedPosition: number,
  loadedTrackId: string | null,
  expectedTrackId: string | undefined,
): boolean {
  return expectedTrackId != null &&
    loadedPosition === expectedPosition &&
    loadedTrackId === expectedTrackId;
}
