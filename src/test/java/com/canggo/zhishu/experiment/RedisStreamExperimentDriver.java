package com.canggo.zhishu.experiment;

import com.canggo.zhishu.service.RedisChatSessionStore;
import com.canggo.zhishu.service.RedisChatSessionStore.ChatMessageRecord;
import com.canggo.zhishu.service.RedisChatSessionStore.ChatSessionMeta;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * ZH-F05 G4 EXPERIMENT 采集驱动 (plain JUnit + Lettuce 直连 redis database 14)。
 *
 * 设计决策 (偏离 02 §7 的 @SpringBootTest 暗示, 更稳):
 *   ZH-F05 度量的是 RedisChatSessionStore 的 Redis 写放大行为, 所有指标 (commandstats/bytes/P95)
 *   均为 Redis 层, 不依赖 Spring 应用上下文. plain JUnit + 手工 Lettuce RedisTemplate 直连
 *   database 14, 完全隔离 ES/Kafka/MinIO/MySQL 依赖 (这些是 @SpringBootTest 的失败点),
 *   仍驱动真实 RedisChatSessionStore 生产代码 (同一 class, 同一 RedisTemplate 抽象).
 *
 * 被测代码: 真实 RedisChatSessionStore (生产 class, 非 mock), StringRedisSerializer (匹配生产
 *   StringRedisTemplate). arm 通过反射注入 experimentFeature/experimentArm/bufferTtlSeconds.
 *
 * 采集协议 (per scale, 同 JVM):
 *   1. setup (不计量): 每个 answer 预建独立 session + seed N 条 history (直接 SET, O(N), 绕过
 *      createUserMessage 的 O(N²) refresh).
 *   2. warmup (不计量): baseline + after 各跑 1 answer 热身 JIT.
 *   3. measure (计量): CONFIG RESETSTAT → 跑 n answer (createAssistantPlaceholder + K chunk +
 *      completeAssistantMessage, nanoTime per chunk) → INFO commandstats/bytes diff.
 *   baseline batch + after batch 分别 RESETSTAT, commandstats diff = 各 arm n answer 总量.
 *
 * commandstats/bytes = GROUND TRUTH (真实 store 代码 × 真实 Redis). 命令数 deterministic, 小 n
 *   即精确; P95 用 per-chunk nanoTime 样本 (n × K).
 *
 * Guard @EnabledIfEnvironmentVariable(ZH_F05_EXP_RUN=true): 正常 mvn test 跳过, 仅 ps1 编排时启用.
 */
@EnabledIfEnvironmentVariable(named = "ZH_F05_EXP_RUN", matches = "true")
class RedisStreamExperimentDriver {

    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplate<String, String> redisTemplate;
    private static RedisChatSessionStore store;
    // 独立原生 Lettuce 连接 (String codec), 仅用于 INFO commandstats 原始字符串读取,
    // 绕过 Spring Data Redis Properties 按**首个冒号**切分导致 cmdstat:get:calls=N 全塌缩为 key=cmdstat 的 bug.
    private static RedisClient rawRedisClient;
    private static StatefulRedisConnection<String, String> rawConnection;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String USER_ID = "exp-user-f05";
    private static final String EXPERIMENT_FEATURE = "ZH-F05";
    private static final long BUFFER_TTL = 3600L;

    @BeforeAll
    static void setUp() throws Exception {
        String host = env("ZH_F05_REDIS_HOST", "192.168.241.128");
        int port = Integer.parseInt(env("ZH_F05_REDIS_PORT", "6379"));
        String password = env("ZH_F05_REDIS_PASSWORD", "");
        int db = Integer.parseInt(env("ZH_F05_REDIS_DB", "14"));

        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
        cfg.setDatabase(db);
        if (password != null && !password.isEmpty()) {
            cfg.setPassword(password);
        }
        connectionFactory = new LettuceConnectionFactory(cfg);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        StringRedisSerializer serializer = new StringRedisSerializer();
        redisTemplate.setKeySerializer(serializer);
        redisTemplate.setValueSerializer(serializer);
        redisTemplate.setHashKeySerializer(serializer);
        redisTemplate.setHashValueSerializer(serializer);
        redisTemplate.afterPropertiesSet();

        store = new RedisChatSessionStore(redisTemplate, objectMapper);
        setField(store, "experimentFeature", EXPERIMENT_FEATURE);
        setField(store, "bufferTtlSeconds", BUFFER_TTL);

        // 独立原生 Lettuce 连接 (String codec) 用于读 INFO commandstats 原始字符串
        RedisURI uri = RedisURI.builder().withHost(host).withPort(port).withDatabase(db)
                .withPassword(password == null ? "" : password).build();
        rawRedisClient = RedisClient.create(uri);
        rawConnection = rawRedisClient.connect();

        // 隔离确认: 连接到目标 db 后 flush, 确保 database 14 干净 (仅 ZH-F05 实验使用)
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.flushDb();
            return null;
        });
        System.out.println("[ZH-F05-EXP] connected to " + host + ":" + port + " db=" + db + " (flushed)");
    }

    @AfterAll
    static void tearDown() {
        if (rawConnection != null) {
            rawConnection.close();
        }
        if (rawRedisClient != null) {
            rawRedisClient.shutdown();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void runExperiment() throws Exception {
        String scale = env("ZH_F05_SCALE", "M");
        int n = Integer.parseInt(env("ZH_F05_N", "10"));
        Path datasetDir = Paths.get(env("ZH_F05_DATASET_DIR", "."));
        Path outDir = Paths.get(env("ZH_F05_OUT_DIR", "."));
        Files.createDirectories(outDir);

        List<String> chunks = readJson(datasetDir.resolve("chunks-" + scale + ".json"), new TypeReference<>() {});
        List<HistoryMsg> history = readJson(datasetDir.resolve("history-" + scale + ".json"), new TypeReference<>() {});
        int chunkCount = chunks.size();
        int historySize = history.size();
        System.out.println("[ZH-F05-EXP] scale=" + scale + " n=" + n + " chunkCount=" + chunkCount + " historySize=" + historySize);

        // ---- PHASE 1: setup (不计量) — 预建 n+1 个 session 各带 N history ----
        // 多建 1 个用于 warmup (warmup 不计入计量 n)
        String[] baselineSessionIds = new String[n + 1];
        String[] afterSessionIds = new String[n + 1];
        for (int i = 0; i <= n; i++) {
            baselineSessionIds[i] = seedSession(history);
            afterSessionIds[i] = seedSession(history);
        }
        System.out.println("[ZH-F05-EXP] setup done: " + (2 * (n + 1)) + " sessions seeded with " + historySize + " history each");

        Map<String, Object> armBaseline = measureArm("baseline", baselineSessionIds, chunks, n);
        Map<String, Object> armAfter = measureArm("after", afterSessionIds, chunks, n);

        // ---- canonical diff (ZH-M-F05-04): baseline vs after 最终消息逐 answer 字节等价 ----
        @SuppressWarnings("unchecked")
        List<String> baselineFinals = (List<String>) armBaseline.get("finalContents");
        @SuppressWarnings("unchecked")
        List<String> afterFinals = (List<String>) armAfter.get("finalContents");
        int canonicalMatch = 0;
        List<Map<String, Object>> canonicalDetails = new ArrayList<>();
        for (int i = 0; i < Math.min(baselineFinals.size(), afterFinals.size()); i++) {
            boolean eq = baselineFinals.get(i).equals(afterFinals.get(i));
            if (eq) canonicalMatch++;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("answerIndex", i);
            d.put("equal", eq);
            d.put("baselineLen", baselineFinals.get(i).length());
            d.put("afterLen", afterFinals.get(i).length());
            canonicalDetails.add(d);
        }
        int canonicalTotal = Math.min(baselineFinals.size(), afterFinals.size());
        double canonicalPct = canonicalTotal == 0 ? 0.0 : (100.0 * canonicalMatch / canonicalTotal);

        // ---- 汇总 + 写 result-<scale>.json ----
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("featureId", "ZH-F05");
        result.put("experimentId", "ZH-EXP-F05-REDIS-STREAM-BUFFER");
        result.put("scale", scale);
        result.put("n", n);
        result.put("chunkCount", chunkCount);
        result.put("historySize", historySize);
        result.put("historyEffectiveN", historySize + 1); // history + 1 placeholder visible during streaming
        result.put("armBaseline", armBaseline);
        result.put("armAfter", armAfter);
        result.put("canonicalDiff", Map.of(
                "match", canonicalMatch, "total", canonicalTotal, "pct", canonicalPct,
                "details", canonicalDetails));
        result.put("note", "commands/answer & bytes/answer from Redis INFO commandstats/network diff over n answers (createAssistantPlaceholder + K chunk + completeAssistantMessage). Deterministic command count, small n exact; P95 from per-chunk nanoTime.");

        Path outFile = outDir.resolve("result-" + scale + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outFile.toFile(), result);
        System.out.println("[ZH-F05-EXP] wrote " + outFile);

        // 控制台摘要 (ps1 捕获)
        System.out.println("[ZH-F05-EXP-SUMMARY] scale=" + scale);
        printSummary("baseline", armBaseline);
        printSummary("after", armAfter);
        @SuppressWarnings("unchecked")
        Map<String, Long> bc = (Map<String, Long>) armBaseline.get("commandTotals");
        @SuppressWarnings("unchecked")
        Map<String, Long> ac = (Map<String, Long>) armAfter.get("commandTotals");
        long bCmds = totalCommands(bc);
        long aCmds = totalCommands(ac);
        double cmdReduction = bCmds == 0 ? 0.0 : 100.0 * (bCmds - aCmds) / bCmds;
        System.out.println("[ZH-F05-EXP-SUMMARY] commands/answer baseline=" + (bCmds / (double) n) + " after=" + (aCmds / (double) n) + " reduction%=" + String.format("%.2f", cmdReduction));
        System.out.println("[ZH-F05-EXP-SUMMARY] canonical diff match=" + canonicalMatch + "/" + canonicalTotal + " (" + String.format("%.2f", canonicalPct) + "%)");
    }

    private Map<String, Object> measureArm(String arm, String[] sessionIds, List<String> chunks, int n) {
        setField(store, "experimentArm", arm);
        int chunkCount = chunks.size();

        // warmup 1 answer (index n, 不计量) — 热身 JIT/Lettuce 连接, 不计入计量窗口
        runOneAnswer(sessionIds[n], chunks, chunkCount, null);

        // MEASURE 窗口: RESETSTAT (仅清统计, 不清 keys; PHASE 1 seed 的 session 持续存在) + capture before
        resetStats();
        Properties infoBefore = infoAll();
        long bytesInBefore = parseLong(infoBefore, "total_net_input_bytes");
        long bytesOutBefore = parseLong(infoBefore, "total_net_output_bytes");
        String cmdstatBefore = infoCommandStatsRaw();

        List<Long> perChunkNanos = new ArrayList<>(n * chunkCount);
        List<String> finalContents = new ArrayList<>(n);
        long tArmStart = System.nanoTime();

        for (int i = 0; i < n; i++) {
            runOneAnswer(sessionIds[i], chunks, chunkCount, perChunkNanos, finalContents);
        }
        long armElapsedMs = (System.nanoTime() - tArmStart) / 1_000_000;

        Properties infoAfter = infoAll();
        String cmdstatAfter = infoCommandStatsRaw();
        long bytesInAfter = parseLong(infoAfter, "total_net_input_bytes");
        long bytesOutAfter = parseLong(infoAfter, "total_net_output_bytes");

        Map<String, Long> commandTotals = commandDeltas(cmdstatBefore, cmdstatAfter);
        long bytesInDelta = bytesInAfter - bytesInBefore;
        long bytesOutDelta = bytesOutAfter - bytesOutBefore;

        double[] p = percentiles(perChunkNanos);
        Map<String, Object> armResult = new LinkedHashMap<>();
        armResult.put("arm", arm);
        armResult.put("n", n);
        armResult.put("chunkCount", chunkCount);
        armResult.put("commandTotals", commandTotals);
        armResult.put("totalCommands", totalCommands(commandTotals));
        armResult.put("commandsPerAnswer", totalCommands(commandTotals) / (double) n);
        armResult.put("netInputBytesDelta", bytesInDelta);
        armResult.put("netOutputBytesDelta", bytesOutDelta);
        armResult.put("netInputBytesPerAnswer", bytesInDelta / (double) n);
        armResult.put("netOutputBytesPerAnswer", bytesOutDelta / (double) n);
        armResult.put("armElapsedMs", armElapsedMs);
        armResult.put("perChunkCount", perChunkNanos.size());
        armResult.put("perChunkP50Nanos", p[0]);
        armResult.put("perChunkP95Nanos", p[1]);
        armResult.put("perChunkMinNanos", p[2]);
        armResult.put("perChunkMaxNanos", p[3]);
        armResult.put("perChunkMeanNanos", perChunkNanos.isEmpty() ? 0 : perChunkNanos.stream().mapToLong(Long::longValue).average().orElse(0));
        armResult.put("finalContents", finalContents);
        return armResult;
    }

    private void runOneAnswer(String sessionId, List<String> chunks, int chunkCount, List<Long> perChunkNanos) {
        runOneAnswer(sessionId, chunks, chunkCount, perChunkNanos, null);
    }

    private void runOneAnswer(String sessionId, List<String> chunks, int chunkCount, List<Long> perChunkNanos, List<String> finalCollector) {
        ChatMessageRecord placeholder = store.createAssistantPlaceholder(USER_ID, sessionId);
        String messageId = placeholder.messageId();
        for (int c = 0; c < chunkCount; c++) {
            long t0 = System.nanoTime();
            store.appendAssistantChunk(USER_ID, messageId, chunks.get(c));
            long dt = System.nanoTime() - t0;
            if (perChunkNanos != null) perChunkNanos.add(dt);
        }
        ChatMessageRecord fin = store.completeAssistantMessage(USER_ID, messageId, Collections.emptyMap());
        if (finalCollector != null) finalCollector.add(fin.content() == null ? "" : fin.content());
    }

    // ---- session seeding (direct SET, O(N), bypass createUserMessage O(N²) refresh) ----
    // 预建一个带 N 条 finished history 的 session: N 条 message key + 1 messages-list + 1 meta + 1 SADD + seq=N.
    // setup 阶段直接 SET, 不经 store.create* (避免每条触发的 O(i) refresh). 测量阶段仍用真实 store.
    private String seedSession(List<HistoryMsg> history) {
        String sessionId = UUID.randomUUID().toString();
        int n = history.size();
        List<String> messageIds = new ArrayList<>(n);
        try {
            for (int i = 0; i < n; i++) {
                HistoryMsg h = history.get(i);
                String mid = "hist-" + sessionId + "-" + i;
                messageIds.add(mid);
                ChatMessageRecord rec = new ChatMessageRecord(
                        mid, sessionId, USER_ID, h.role, h.content, i + 1, "finished",
                        "2026-07-08T10:00:00", "2026-07-08T10:00:00", 1, 0, null, false, null, true, false);
                redisTemplate.opsForValue().set("chat:message:" + mid, objectMapper.writeValueAsString(rec));
            }
            redisTemplate.opsForValue().set("chat:session:" + sessionId + ":messages", objectMapper.writeValueAsString(messageIds));
            ChatSessionMeta meta = new ChatSessionMeta(
                    sessionId, USER_ID, "exp session", "2026-07-08T10:00:00", "2026-07-08T10:00:00",
                    n > 0 ? "2026-07-08T10:00:00" : null, n,
                    n > 0 ? history.get(n - 1).content : "", false,
                    n > 0 ? messageIds.get(0) : null, n > 0 ? messageIds.get(n - 1) : null, null);
            redisTemplate.opsForValue().set("chat:session:" + sessionId + ":meta", objectMapper.writeValueAsString(meta));
            redisTemplate.opsForSet().add("chat:user:" + USER_ID + ":sessions", sessionId);
            redisTemplate.opsForValue().increment("chat:session:" + sessionId + ":seq", n);
            return sessionId;
        } catch (Exception e) {
            throw new RuntimeException("seedSession failed", e);
        }
    }

    // ---- Redis info helpers ----
    private void resetStats() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().resetConfigStats();
            return null;
        });
    }

    private Properties infoAll() {
        return redisTemplate.execute((RedisCallback<Properties>) connection -> connection.info());
    }

    // Spring Data Redis 的 connection.info("commandstats") 把 cmdstat:get:calls=N 按**首个冒号**切分成
    // key=cmdstat (所有命令行塌缩成一个 key), 无法用 startsWith("cmdstat:") 过滤. 改走独立原生 Lettuce
    // String-codec 连接的 sync().info("commandstats") 拿原始多行字符串, 自行解析 cmdstat:CMD:calls=N.
    private String infoCommandStatsRaw() {
        return rawConnection.sync().info("commandstats");
    }

    private static long parseLong(Properties p, String key) {
        String v = p == null ? null : p.getProperty(key);
        return v == null ? 0L : Long.parseLong(v.trim());
    }

    private static Map<String, Long> commandDeltas(String rawBefore, String rawAfter) {
        Map<String, Long> before = parseCommandStats(rawBefore);
        Map<String, Long> after = parseCommandStats(rawAfter);
        Map<String, Long> deltas = new LinkedHashMap<>();
        for (String cmd : after.keySet()) {
            long beforeCalls = before.getOrDefault(cmd, 0L);
            long afterCalls = after.getOrDefault(cmd, 0L);
            deltas.put(cmd, afterCalls - beforeCalls);
        }
        return deltas;
    }

    // 解析 INFO commandstats 原始输出. Redis 7 标准格式 "cmdstat:<cmd>:calls=N,...",
    // 但本实验 Redis (192.168.241.128) 返回 "cmdstat_<cmd>:calls=N,..." (下划线分隔 cmdstat 与 cmd).
    // 两种前缀均为 8 字符, 统一 strip 后取到下一个 ':' 为 cmd 名. <cmd> 可能含 | (子命令如 config|resetstat).
    private static Map<String, Long> parseCommandStats(String raw) {
        Map<String, Long> map = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String line : raw.split("\\r?\\n")) {
            if (!line.startsWith("cmdstat:") && !line.startsWith("cmdstat_")) continue;
            String rest = line.substring(8); // strip "cmdstat:" 或 "cmdstat_"
            int colonIdx = rest.indexOf(':');
            if (colonIdx < 0) continue;
            String cmd = rest.substring(0, colonIdx);
            String value = rest.substring(colonIdx + 1);
            map.put(cmd, parseCalls(value));
        }
        return map;
    }

    private static long parseCalls(String cmdstatValue) {
        // e.g. "calls=123,usec=456,usec_per_call=3.71,rejected_calls=0,failed_calls=0"
        if (cmdstatValue == null) return 0L;
        for (String part : cmdstatValue.split(",")) {
            if (part.startsWith("calls=")) {
                return Long.parseLong(part.substring("calls=".length()).trim());
            }
        }
        return 0L;
    }

    private static long totalCommands(Map<String, Long> deltas) {
        return deltas.values().stream().mapToLong(Long::longValue).sum();
    }

    private static double[] percentiles(List<Long> samples) {
        // [p50, p95, min, max] in nanos; empty → zeros
        if (samples == null || samples.isEmpty()) return new double[]{0, 0, 0, 0};
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int n = sorted.size();
        long p50 = sorted.get((int) Math.min(n - 1, (long) (0.50 * (n - 1))));
        long p95 = sorted.get((int) Math.min(n - 1, (long) Math.ceil(0.95 * (n - 1))));
        long min = sorted.get(0);
        long max = sorted.get(n - 1);
        return new double[]{p50, p95, min, max};
    }

    // ---- generic helpers ----
    private static String env(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static <T> T readJson(Path path, TypeReference<T> type) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return objectMapper.readValue(bytes, type);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("setField " + name + " failed", e);
        }
    }

    private void printSummary(String arm, Map<String, Object> a) {
        System.out.println("[ZH-F05-EXP-SUMMARY] " + arm + ": commands/answer=" + a.get("commandsPerAnswer")
                + " bytesIn/answer=" + a.get("netInputBytesPerAnswer")
                + " bytesOut/answer=" + a.get("netOutputBytesPerAnswer")
                + " P95ns=" + a.get("perChunkP95Nanos")
                + " P50ns=" + a.get("perChunkP50Nanos")
                + " elapsedMs=" + a.get("armElapsedMs"));
    }

    // dataset history entry
    public static class HistoryMsg {
        public int seq;
        public String role;
        public String content;
    }
}
