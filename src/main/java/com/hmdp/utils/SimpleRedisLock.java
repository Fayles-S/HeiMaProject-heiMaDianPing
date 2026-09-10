package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock
{
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static
    {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name , StringRedisTemplate stringRedisTemplate)
    {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public boolean tryLock(Long timeoutSeconds)
    {
        String lockKey = KEY_PREFIX + name;
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, threadId, timeoutSeconds, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    //LUA脚本实现原子性保证锁
    @Override
    public void unlock()
    {
        stringRedisTemplate.execute
                (
                        UNLOCK_SCRIPT,
                        Collections.singletonList(KEY_PREFIX + name),
                        ID_PREFIX + Thread.currentThread().getId()
                );
    }

    /*@Override
    public void unlock()
    {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String redisId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if(redisId.equals(threadId))
        {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
