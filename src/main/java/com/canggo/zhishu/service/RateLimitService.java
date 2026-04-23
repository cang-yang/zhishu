package com.canggo.zhishu.service;

import com.canggo.zhishu.config.RateLimitProperties;
import com.canggo.zhishu.exception.RateLimitExceededException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class RateLimitService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RateLimitProperties properties;
    private final RateLimitConfigService rateLimitConfigService;
    private final UsageQuotaService usageQuotaService;

    public RateLimitService(
            StringRedisTemplate stringRedisTemplate,
            RateLimitProperties properties,
            RateLimitConfigService rateLimitConfigService,
            UsageQuotaService usageQuotaService
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.rateLimitConfigService = rateLimitConfigService;
        this.usageQuotaService = usageQuotaService;
    }

    public void checkRegisterByIp(String ip) {
        checkSingleWindow("register:ip:" + ip, properties.getRegister().getMax(), properties.getRegister().getWindowSeconds(), "注册请求过于频繁");
    }

    public void checkLoginByIp(String ip) {
        checkSingleWindow("login:ip:" + ip, properties.getLogin().getMax(), properties.getLogin().getWindowSeconds(), "登录请求过于频繁");
    }

    public void checkChatByUser(String userId) {
        RateLimitConfigService.WindowLimitView limit = rateLimitConfigService.getCurrentSettings().chatMessage();
        checkSingleWindow("chat:user:" + userId, limit.max(), limit.windowSeconds(), "聊天请求过于频繁");
        usageQuotaService.recordChatRequest(userId);
    }

    public UsageQuotaService.TokenReservationBundle reserveLlmUsage(
            String userId,
            int estimatedPromptTokens,
            int maxCompletionTokens
    ) {
        RateLimitConfigService.TokenBudgetView limit = rateLimitConfigService.getCurrentSettings().llmGlobalToken();
        return usageQuotaService.reserveLlmTokensWithGlobalBudget(
                userId,
                estimatedPromptTokens,
                maxCompletionTokens,
                limit.minuteMax(),
                limit.minuteWindowSeconds(),
                limit.dayMax(),
                limit.dayWindowSeconds()
        );
    }

    public void checkEmbeddingQueryByUser(String userId) {
        RateLimitConfigService.DualWindowLimitView limit = rateLimitConfigService.getCurrentSettings().embeddingQueryRequest();
        checkSingleWindow("embedding:query:min:user:" + userId, limit.minuteMax(), limit.minuteWindowSeconds(), "Embedding查询过于频繁");
        checkSingleWindow("embedding:query:day:user:" + userId, limit.dayMax(), limit.dayWindowSeconds(), "Embedding查询当日次数已达上限");
    }

    public UsageQuotaService.TokenReservationBundle reserveEmbeddingUploadUsage(String userId, java.util.List<String> texts) {
        RateLimitConfigService.TokenBudgetView limit = rateLimitConfigService.getCurrentSettings().embeddingUploadToken();
        return usageQuotaService.reserveEmbeddingTokensWithGlobalBudget(
                userId,
                texts,
                "embedding-upload",
                "Embedding上传全网分钟Token预算已达上限",
                "Embedding上传全网当日Token预算已达上限",
                limit.minuteMax(),
                limit.minuteWindowSeconds(),
                limit.dayMax(),
                limit.dayWindowSeconds()
        );
    }

    public UsageQuotaService.TokenReservationBundle reserveEmbeddingQueryUsage(String userId, java.util.List<String> texts) {
        checkEmbeddingQueryByUser(userId);
        RateLimitConfigService.TokenBudgetView limit = rateLimitConfigService.getCurrentSettings().embeddingQueryGlobalToken();
        return usageQuotaService.reserveEmbeddingTokensWithGlobalBudget(
                userId,
                texts,
                "embedding-query",
                "Embedding查询全网分钟Token预算已达上限",
                "Embedding查询全网当日Token预算已达上限",
                limit.minuteMax(),
                limit.minuteWindowSeconds(),
                limit.dayMax(),
                limit.dayWindowSeconds()
        );
    }

    private void checkSingleWindow(String key, long max, long windowSeconds, String message) {
//        Long current = stringRedisTemplate.opsForValue().increment(key);
//        if (current == null) {
//            return;
//        }
//
//        if (current == 1) {
//            stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
//        }
//
//        if (current > max) {
//            Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
//            long retryAfterSeconds = ttl == null || ttl < 0 ? windowSeconds : ttl;
//            throw new RateLimitExceededException(message, retryAfterSeconds);
//        }


        //获取时间
        long now = System.currentTimeMillis();
        long min = now-(windowSeconds)*1000;
        //使用Pipelined管道减少网络io优化性能
        List<Object> list = stringRedisTemplate.executePipelined((RedisCallback<Object>) conn ->{
            byte[] bytes = key.getBytes();
            //删除过期缓存
            conn.zSetCommands().zRemRangeByScore(bytes, 0, min);
            //添加新缓存
            conn.zSetCommands().zAdd(bytes,now,(now+"-"+Math.random()).getBytes());
            //查询数量
            conn.zSetCommands().zCard(bytes);
            //设置过期时间
            conn.keyCommands().expire(bytes, windowSeconds);

            return null;

        });

        //获取返回值中的第三个，也就是查询数量的返回值
        Long count = (Long)list.get(2);
        //先设置一个等待时间，防止rides挂了的情况下获取不到时间
        long time = windowSeconds;
        if(count!=null&&count>max){
            Set<String> set=stringRedisTemplate.opsForZSet().range(key,0,0);
            if(set!=null&&!set.isEmpty()){
                //因为set没有索引，所以iterator()获取指针，next()获取当前指针元素并指向下一个
                String time1=set.iterator().next();
                //转化为long并计算
                time = windowSeconds-(now-Long.parseLong(time1.split("-")[0]))/1000+1;
            }
            throw new RateLimitExceededException(message, time);
        }
    }
}
