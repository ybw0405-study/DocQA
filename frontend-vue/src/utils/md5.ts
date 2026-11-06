import SparkMD5 from 'spark-md5';

/** 读盘分片大小：上传分片固定 5MiB，MD5 用更小的切片换取进度粒度与主线程空闲。 */
const HASH_CHUNK_BYTES = 2 * 1024 * 1024;

export type Md5Progress = (hashedBytes: number, totalBytes: number) => void;

/**
 * 分片增量计算整文件 MD5，输出契约要求的 `^[a-f0-9]{32}$`。
 * 只在分片之间让出主线程，单分片文件不产生额外宏任务，调用方因此能在假定时器下直接 await。
 */
export async function md5OfFile(file: Blob, onProgress?: Md5Progress, signal?: AbortSignal): Promise<string> {
  const spark = new SparkMD5.ArrayBuffer();
  let offset = 0;
  let first = true;
  while (offset < file.size) {
    if (signal?.aborted === true) {
      throw new Error('上传已取消');
    }
    if (!first) {
      await new Promise((resolve) => {
        setTimeout(resolve, 0);
      });
    }
    const end = Math.min(offset + HASH_CHUNK_BYTES, file.size);
    spark.append(await file.slice(offset, end).arrayBuffer());
    offset = end;
    first = false;
    onProgress?.(offset, file.size);
  }
  return spark.end();
}
