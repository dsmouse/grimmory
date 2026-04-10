export function computeGridColumns(containerWidth: number, minColumnWidth: number, gap: number = 21): number {
  if (containerWidth <= 0 || minColumnWidth <= 0) {
    return 1;
  }
  return Math.max(1, Math.floor((containerWidth + gap) / (minColumnWidth + gap)));
}
