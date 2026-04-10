import { describe, it, expect } from 'vitest';
import { chunk } from './array.util';

describe('ArrayUtils', () => {
  describe('chunk', () => {
    it('should split an array into chunks of the specified size', () => {
      const input = [1, 2, 3, 4, 5];
      const result = chunk(input, 2);
      expect(result).toEqual([[1, 2], [3, 4], [5]]);
    });

    it('should return an empty array if the input array is empty', () => {
      const input: number[] = [];
      const result = chunk(input, 2);
      expect(result).toEqual([]);
    });

    it('should return the original array in a single chunk if the size is larger than the array length', () => {
      const input = [1, 2, 3];
      const result = chunk(input, 5);
      expect(result).toEqual([[1, 2, 3]]);
    });

    it('should handle a chunk size of 1', () => {
      const input = [1, 2, 3];
      const result = chunk(input, 1);
      expect(result).toEqual([[1], [2], [3]]);
    });

    it('should return the entire array in a single chunk if the size is 0 or negative', () => {
      const input = [1, 2, 3];
      expect(chunk(input, 0)).toEqual([[1, 2, 3]]);
      expect(chunk(input, -1)).toEqual([[1, 2, 3]]);
    });
  });
});
