package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1767225600L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyPrefix){
        //1.生成时间戳
        long noewSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timestamp = noewSecond - BEGIN_TIMESTAMP;
        //2.生成序列号
        String format = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long increment = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + format);//在Redis中自增后返回value值
        //3.拼接并返回
        return timestamp << COUNT_BITS | increment;
    }
    public static void main(String[] args){
        LocalDateTime localDateTime = LocalDateTime.of(2026, 1, 1, 0, 0);
        long second = localDateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }
}
