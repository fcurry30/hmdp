package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Data
@NoArgsConstructor
public class SimpleRedisLock implements ILock{
    private StringRedisTemplate stringRedisTemplate;
    private static final String key_prefix = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    private String name;

    @Override
    public boolean trylock(long timeoutSec) {
        long id = Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key_prefix + name, ID_PREFIX + id, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//自动拆装箱时一定要注意空指针的风险。
    }
    @Override
    public void unlock() {  //调用lua脚本
        //调用脚本
        stringRedisTemplate.execute(
          UNLOCK_SCRIPT
          , Collections.singletonList(key_prefix + name)
          , ID_PREFIX + Thread.currentThread().getId()
        );

    }

//    @Override
//    public void unlock() {
//        String s = stringRedisTemplate.opsForValue().get(key_prefix + name);
//        String s1 = ID_PREFIX + Thread.currentThread().getId();
//        if(s1.equals(s)){
//            stringRedisTemplate.delete(key_prefix + name);
//        }
//    }
}
