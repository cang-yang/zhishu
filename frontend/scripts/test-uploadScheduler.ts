/**
 * ZH-F02 G2 — runUploadPool 单元测试 (tsx 直接运行, 无需 vitest) 验证: 全量执行 / 串行等价 / fail-fast / 空输入 / R10 并集单调 (响应乱序)
 *
 * 运行: pnpm tsx scripts/test-uploadScheduler.ts 退出码: 0=全 PASS, 1=有 FAIL
 */
import process from 'node:process';
import { runUploadPool } from '../src/utils/uploadScheduler';

const sleep = (ms: number) =>
  new Promise<void>(r => {
    setTimeout(r, ms);
  });

type Case = { name: string; run: () => Promise<void> };
const cases: Case[] = [];
let failures = 0;

function test(name: string, run: () => Promise<void>) {
  cases.push({ name, run });
}

function assert(cond: boolean, msg: string) {
  if (!cond) throw new Error(`ASSERT FAIL: ${msg}`);
}
function eq<T>(actual: T, expected: T, msg: string) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) throw new Error(`ASSERT FAIL: ${msg}\n  expected: ${e}\n  actual:   ${a}`);
}

// Case 1: 全量执行 (concurrency=4, 10 indices)
test('all-indices-executed (c=4, n=10)', async () => {
  const done: number[] = [];
  await runUploadPool([0, 1, 2, 3, 4, 5, 6, 7, 8, 9], 4, async i => {
    done.push(i);
    await sleep(1);
  });
  eq(
    done.slice().sort((x, y) => x - y),
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
    'all 10 indices executed exactly once'
  );
  eq(new Set(done).size, 10, 'no duplicate execution');
});

// Case 1b: 断点续传的待上传分片可能不连续，必须派发真实分片编号而非数组位置
test('sparse-indices-preserved (c=2)', async () => {
  const done: number[] = [];
  await runUploadPool([2, 5, 7], 2, async i => {
    done.push(i);
    await sleep(1);
  });
  eq(
    done.slice().sort((x, y) => x - y),
    [2, 5, 7],
    'dispatches the sparse chunk indices exactly once'
  );
});

// Case 2: concurrency=1 严格串行 (顺序保持, 无并发重叠)
test('serial-order-strict (c=1)', async () => {
  const order: number[] = [];
  let active = 0;
  let maxActive = 0;
  await runUploadPool([0, 1, 2, 3, 4], 1, async i => {
    active += 1;
    maxActive = Math.max(maxActive, active);
    order.push(i);
    await sleep(2);
    active -= 1;
  });
  eq(order, [0, 1, 2, 3, 4], 'serial preserves order');
  assert(maxActive === 1, `serial never overlaps (maxActive=${maxActive})`);
});

// Case 2b: concurrency>1 确实并发 (maxActive > 1)
test('concurrent-actually-parallel (c=4)', async () => {
  let active = 0;
  let maxActive = 0;
  await runUploadPool([0, 1, 2, 3], 4, async () => {
    active += 1;
    maxActive = Math.max(maxActive, active);
    await sleep(10);
    active -= 1;
  });
  assert(maxActive === 4, `c=4 reaches 4 concurrent (maxActive=${maxActive})`);
});

// Case 3: fail-fast — 首个 worker 抛错即停止派发, reject
test('fail-fast-on-worker-error', async () => {
  const processed: number[] = [];
  let threw = false;
  try {
    await runUploadPool([0, 1, 2, 3, 4, 5, 6, 7], 2, async i => {
      if (i === 2) throw new Error(`boom at ${i}`);
      processed.push(i);
      await sleep(5);
    });
  } catch (e) {
    threw = true;
    assert(String(e).includes('boom at 2'), `rethrows first error: ${e}`);
  }
  assert(threw, 'rejects on worker error');
  // fail-fast: 不保证 processed 排除所有 >cursor 的, 但 2 必不在内, 且非全量执行
  assert(!processed.includes(2), 'failing index not marked processed');
  assert(processed.length < 8, `stops dispatching (processed=${processed.length} < 8)`);
});

// Case 4: 空 indices 立即 resolve
test('empty-indices-resolves-immediately', async () => {
  let called = false;
  await runUploadPool([], 4, async () => {
    called = true;
  });
  assert(!called, 'worker never called on empty indices');
});

// Case 5: concurrency 超过 indices 数量时 clamp (n = min(c, len))
test('concurrency-clamped-to-indices-length', async () => {
  const done: number[] = [];
  await runUploadPool([0, 1], 100, async i => {
    done.push(i);
    await sleep(1);
  });
  eq(
    done.slice().sort((x, y) => x - y),
    [0, 1],
    'clamped pool still completes all'
  );
});

// Case 6 (R10): 并集单调 — 响应乱序到达 (后到的 stale 快照) 不使 uploadedChunks 倒退
// 模拟后端 getUploadedChunks 返回当时刻 bitmap 快照 (可能不含其他在飞 worker SETBIT)
test('R10-union-monotonic-under-response-reorder', async () => {
  const total = 6;
  // 每个 worker 完成时, 后端快照 = 此刻已 SETBIT 的 index 集合 (按完成时间序累积)
  // 用 runUploadPool 并发, 强制小 index 后完成 (sleep 反比), 验证并集公式不倒退
  let unionList: number[] = [];
  const completionOrder: number[] = [];
  await runUploadPool(
    Array.from({ length: total }, (_, i) => i),
    total,
    async i => {
      // 强制 index 越小 sleep 越久 → 完成序为 5,4,3,2,1,0 (反序)
      await sleep((total - i) * 15);
      completionOrder.push(i);
      // 模拟后端快照: 此刻已完成的 index (completionOrder 是完成序)
      const snapshot = [...completionOrder];
      // R10 修复: 并集 (生产代码 index.ts:85 同公式)
      unionList = Array.from(new Set([...unionList, ...snapshot])).sort((a, b) => a - b);
    }
  );
  // 反序完成验证
  assert(completionOrder[0] === total - 1, `last index completes first (got ${completionOrder[0]})`);
  // 并集单调: 最终含全部分片, merge 判定 length===totalChunks 成立
  eq(unionList, [0, 1, 2, 3, 4, 5], 'union monotonic → all chunks present → merge triggers');
});

// Case 6b (R10 对比): 替换 (replace, 旧 buggy 逻辑) 在乱序下会倒退 — 证明修复必要
test('R10-replace-LOSES-data-under-reorder (negative proof)', async () => {
  const total = 4;
  // 直接模拟响应到达序: [0,1,2,3] 的快照按完成序 [3,2,1,0] 到达
  const responses: number[][] = [
    [0, 1, 2, 3], // worker 3 完成时后端已见全量
    [0, 1, 2], // worker 2
    [0, 1], // worker 1
    [0] // worker 0 完成时 (反序到达, 后端快照仅 [0]) — stale
  ];
  // replace (旧): 后到的 [0] 覆盖
  let replaceList: number[] = [];
  for (const r of responses) replaceList = r;
  // union (新/R10): 并集
  let unionList: number[] = [];
  for (const r of responses) unionList = Array.from(new Set([...unionList, ...r])).sort((a, b) => a - b);

  eq(replaceList, [0], 'REPLACE loses data → merge would NOT trigger (bug repro)');
  eq(unionList, [0, 1, 2, 3], 'UNION preserves data → merge triggers (fix)');
  assert(unionList.length === total, 'union reaches totalChunks → merge');
  assert(replaceList.length !== total, 'replace fails to reach totalChunks → silent stuck (bug)');
});

// Run all
(async () => {
  for (const c of cases) {
    try {
      // eslint-disable-next-line no-await-in-loop
      await c.run();
      console.log(`  PASS  ${c.name}`);
    } catch (e) {
      failures += 1;
      console.error(`  FAIL  ${c.name}\n        ${e instanceof Error ? e.message : String(e)}`);
    }
  }
  console.log(`\n${cases.length - failures}/${cases.length} passed`);
  if (failures > 0) {
    console.error(`FAILED ${failures} case(s)`);
    process.exit(1);
  }
  console.log('ALL PASS');
  process.exit(0);
})();
