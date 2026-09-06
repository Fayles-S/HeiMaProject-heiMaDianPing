package com.hmdp.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class RedisIdWorker
{
    private static final long BEGIN_TIMESTAMP = 1767225600;
    private static final int COUNT_BITS = 32;
    private final StringRedisTemplate stringRedisTemplate;


    public long nextId(String keyPrefix)
    {
        long now = LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli();
        long timeStamp = now - BEGIN_TIMESTAMP;

        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + time);
        return timeStamp << COUNT_BITS | count;
    }
}
