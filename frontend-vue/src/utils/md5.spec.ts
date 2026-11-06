import { describe, expect, it, vi } from 'vitest';
import SparkMD5 from 'spark-md5';
import { md5OfFile } from './md5';

function fileOf(segments: number[][], name: string): File {
  return new File(segments.map((bytes) => new Uint8Array(bytes)), name);
}

describe('md5OfFile', () => {
  it('matches the reference MD5 for known content', async () => {
    const file = new File(['hello'], 'hello.txt', { type: 'text/plain' });

    await expect(md5OfFile(file)).resolves.toBe('5d41402abc4b2a76b9719d911017c592');
  });

  it('returns a lowercase 32-hex digest the contract accepts', async () => {
    const file = fileOf([Array.from({ length: 4096 }, (_, i) => i % 256)], 'noise.bin');

    await expect(md5OfFile(file)).resolves.toMatch(/^[a-f0-9]{32}$/);
  });

  it('produces the same digest across chunk boundaries as a single-buffer hash', async () => {
    // 3MiB 会走两次读盘分片（2MiB + 1MiB），正好覆盖增量状态跨块延续的路径。
    const segments = [new Uint8Array(2 * 1024 * 1024).fill(7), new Uint8Array(1024 * 1024).fill(9)];
    const file = new File(segments, 'big.bin');
    const whole = new Uint8Array(3 * 1024 * 1024);
    whole.set(segments[0], 0);
    whole.set(segments[1], segments[0].length);

    const incremental = await md5OfFile(file);
    expect(incremental).toBe(SparkMD5.ArrayBuffer.hash(whole.buffer));
    expect(incremental).not.toBe(SparkMD5.ArrayBuffer.hash(segments[0].buffer));
  });

  it('reports monotonic progress that ends at the file size', async () => {
    const file = new File([new Uint8Array(3 * 1024 * 1024).fill(3)], 'big.bin');
    const seen: number[] = [];

    await md5OfFile(file, (hashedBytes, totalBytes) => {
      seen.push(hashedBytes);
      expect(totalBytes).toBe(file.size);
    });

    expect(seen.at(-1)).toBe(file.size);
    expect(seen.every((value, index) => index === 0 || value > seen[index - 1])).toBe(true);
    expect(seen).toHaveLength(2);
  });

  it('stops between chunks when the caller aborts', async () => {
    const file = new File([new Uint8Array(3 * 1024 * 1024).fill(5)], 'big.bin');
    const controller = new AbortController();
    // 第一块读完即取消，让循环在下一轮开头命中 aborted 分支。
    const reject = md5OfFile(
      file,
      () => controller.abort(),
      controller.signal,
    );

    await expect(reject).rejects.toThrow();
  });

  it('hashes a single-chunk file without scheduling a yield', async () => {
    const timer = vi.spyOn(globalThis, 'setTimeout');
    const file = new File(['small'], 'small.txt', { type: 'text/plain' });

    await md5OfFile(file);

    expect(timer).not.toHaveBeenCalled();
  });
});
