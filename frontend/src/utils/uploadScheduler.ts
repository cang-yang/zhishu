/**
 * 有界并发 worker pool: N workers 从 indices 拉取下一 index 调 worker(i), fail-fast (R3)
 *
 * 任一 worker 抛错 → 停止派发, reject (已派发的 worker 等待完成); 已上传分片由后端 bitmap 保留, 可恢复。 concurrency <= 1 退化为串行 (baseline 兼容, 保证
 * baseline/after 算法等价性 R8)。
 *
 * cursor-based 派发: JS 单线程下 cursor++ 原子, 无锁安全。
 *
 * @param indices 待处理的 index 列表 (如剩余分片号)
 * @param concurrency 并发度 (1=串行; >1=有界并发)
 * @param worker 处理单个 index 的异步函数, 抛错触发 fail-fast
 */
export async function runUploadPool(
  indices: number[],
  concurrency: number,
  worker: (i: number) => Promise<void>
): Promise<void> {
  if (concurrency <= 1) {
    // 串行 baseline 兼容: 顺序 await 是预期行为 (非可并发优化点)
    for (const i of indices) {
      // eslint-disable-next-line no-await-in-loop
      await worker(i);
    }
    return;
  }
  let cursor = 0;
  let firstError: unknown = null;
  const spawn = async () => {
    while (firstError === null) {
      // worker pool: cursor-based 派发, 每个 spawn 顺序处理自己拉取的 index (多 spawn 并发)
      const i = cursor;
      cursor += 1;
      if (i >= indices.length) return;
      try {
        // eslint-disable-next-line no-await-in-loop
        await worker(i);
      } catch (e) {
        firstError ??= e;
        return;
      }
    }
  };
  const n = Math.min(concurrency, indices.length);
  await Promise.all(Array.from({ length: n }, () => spawn()));
  if (firstError !== null) throw firstError;
}
