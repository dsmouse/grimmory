/**
 * Splits an array into chunks of a given size.
 *
 * @param array The array to split.
 * @param size The size of each chunk.
 * @returns An array of chunks.
 */
export function chunk<T>(array: T[], size: number): T[][] {
  if (size <= 0) {
    return array.length > 0 ? [[...array]] : [];
  }

  const chunks: T[][] = [];
  for (let i = 0; i < array.length; i += size) {
    chunks.push(array.slice(i, i + size));
  }
  return chunks;
}
