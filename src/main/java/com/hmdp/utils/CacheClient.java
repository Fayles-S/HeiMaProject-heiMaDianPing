package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_TTL;

/**
 * 缓存工具类：封装 Redis 缓存的常用读写，
 * 以及缓存穿透、缓存击穿(逻辑过期)的通用解决方案。
 * 通过泛型 + Function 传入"查库逻辑"，实现与具体业务解耦。
 */
@Component
public class CacheClient
{

    private final StringRedisTemplate stringRedisTemplate;

    /** 异步重建缓存用的线程池 */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 写入普通缓存（带物理过期时间）
     */
    public void set(String key, Object value, Long time, TimeUnit unit)
    {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 写入"逻辑过期"缓存：把数据 + 逻辑过期时间封装成 RedisData 存入，
     * 不设置物理 TTL（key 永远存在，靠 expireTime 判断是否过期）
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit)
    {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透解决方案：缓存空值
     *
     * @param keyPrefix  缓存 key 前缀（如 cache:shop:）
     * @param id         业务 id
     * @param type       返回类型
     * @param dbFallback 查数据库的回调（传入查库逻辑）
     * @param time       缓存过期时长
     * @param unit       过期时间单位
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit)
    {
        String key = keyPrefix + id;
        // 1. 查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 命中（非空值）→ 直接返回
        if (StrUtil.isNotBlank(json))
        {
            return JSONUtil.toBean(json, type);
        }
        // 3. 命中的是空值（缓存了 ""）→ 说明之前查过不存在，直接返回 null
        if (json != null)
        {
            return null;
        }
        // 4. 未命中 → 查数据库
        R bean = dbFallback.apply(id);
        // 5. 数据库不存在 → 缓存空值，防止穿透
        if (bean == null)
        {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6. 数据库存在 → 写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(bean), time, unit);
        return bean;
    }

    /**
     * 缓存击穿解决方案：逻辑过期（需要先用 setWithLogicalExpire / saveShop2Redis 预热）
     *
     * @param keyPrefix   缓存 key 前缀
     * @param id          业务 id
     * @param type        返回类型
     * @param dbFallback  查数据库的回调
     * @param time        逻辑过期时长
     * @param unit        过期时间单位
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit)
    {
        String key = keyPrefix + id;
        // 1. 查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 缓存为空（未预热/冷启动）→ 逻辑过期方案不处理，返回 null 交给上层兜底
        if (StrUtil.isBlank(json))
        {
            return null;
        }
        // 3. 解析缓存，判断是否逻辑过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R bean = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        // 4. 未过期 → 直接返回
        if (redisData.getExpireTime().isAfter(LocalDateTime.now()))
        {
            return bean;
        }
        // 5. 已过期 → 尝试获取互斥锁
        String lockKey = LOCK_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6. 没抢到锁 → 返回旧数据（抢到锁的线程会去重建）
        if (!isLock)
        {
            return bean;
        }
        // 7. 抢到锁 → double check，防止等锁期间别人已经重建
        try
        {
            json = stringRedisTemplate.opsForValue().get(key);
            RedisData newRedisData = JSONUtil.toBean(json, RedisData.class);
            if (newRedisData.getExpireTime().isAfter(LocalDateTime.now()))
            {
                // 已被别人重建 → 释放锁，返回新数据
                unlock(lockKey);
                return JSONUtil.toBean((JSONObject) newRedisData.getData(), type);
            }
            // 8. 确实还没重建 → 提交异步任务重建，由异步线程重建完后释放锁
            CACHE_REBUILD_EXECUTOR.submit(() ->
            {
                try
                {
                    saveShop2Redis(keyPrefix, id, dbFallback, time, unit);
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
                finally
                {
                    unlock(lockKey);
                }
            });
        }
        catch (Exception e)
        {
            // 出现异常也要释放锁
            unlock(lockKey);
            throw new RuntimeException(e);
        }
        // 9. 返回旧数据（当前请求不等待重建）
        return bean;
    }

    /**
     * 查询数据库并写入"逻辑过期"缓存（供预热和异步重建使用）
     */
    public <R, ID> void saveShop2Redis(String keyPrefix, ID id, Function<ID, R> dbFallback, Long time, TimeUnit unit)
    {
        // 1. 查数据库
        R bean = dbFallback.apply(id);
        // 2. 封装 RedisData（含逻辑过期时间）
        RedisData redisData = new RedisData();
        redisData.setData(bean);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 3. 写入缓存（无物理 TTL）
        stringRedisTemplate.opsForValue().set(keyPrefix + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 删除缓存
     */
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 尝试获取互斥锁（SETNX，带过期时间防止死锁）
     */
    private boolean tryLock(String key)
    {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
