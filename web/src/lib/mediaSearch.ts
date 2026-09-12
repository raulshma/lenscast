// Pure filename search for the gallery grid — the groupByDay pattern. The
// server's `q=` param is future-proofing (today's server ignores it), so the
// client filter is the source of truth: when the server has already filtered,
// every row matches and the filter is a no-op; when it has not, the filter
// removes the non-matching rows. Harmless duplication either way.

export interface HasFileName {
  fileName: string
}

/** Case-insensitive filename substring match; a blank query matches everything. */
export function matchesQuery(fileName: string, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return fileName.toLowerCase().includes(q)
}

/** Keep only the items whose fileName matches the query, order preserved. */
export function filterByQuery<T extends HasFileName>(items: readonly T[], query: string): T[] {
  return items.filter((item) => matchesQuery(item.fileName, query))
}
