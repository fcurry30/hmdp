package com.hmdp.utils;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Data
@NoArgsConstructor
public class SimpleRedisLock implements ILock{
    private StringRedisTemplate stringRedisTemplate;
    private static final String key_prefix = "lock:";
    private String name;

    @Override
    public boolean trylock(long timeoutSec) {
        long id = Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key_prefix + name, id + "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//自动拆装箱时一定要注意空指针的风险。
    }

    @Override
    public void unlock() {
        stringRedisTemplate.delete(key_prefix + name);
    }
}
