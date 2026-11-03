/** Random order of a queue, keeping `keepFirst` (the playing track) in front. */
export function shuffled(q: number[], keepFirst: number) {
  const a = q.filter((x) => x !== keepFirst);
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return keepFirst >= 0 ? [keepFirst, ...a] : a;
}
