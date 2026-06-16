package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,timeUnit);
    }
    public void setWithLogical(String key, Object value, Long time, TimeUnit timeUnit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type
            , Function<ID,R> dbFallback,Long time, TimeUnit timeUnit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        if(json != null){
            return null;//表示里面是空字符串
        }
        //从数据库查询，因此需要Function传递
        R r = dbFallback.apply(id);//获取对应的R
        if(r == null){
            //表示缓存穿透，存空字符串
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        this.set(key,r,time,timeUnit);
        return r;
    }


    /**
     * 获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 基于逻辑过期解决缓存击穿问题
     * @param id
     * @return
     */
    public <R,ID> R queryWithLogicalExpire(String prefix,ID id,Class<R> type
    ,Function<ID,R> dbFallback,Long time, TimeUnit timeUnit){
        //1.从Redis里查询缓存
        String key = prefix + id;
        String shopJson = stringRedisTemplate.opsForValue().get(prefix + id);//这里采用字符串类型演示
        //2.判断是否存在
        if(StrUtil.isBlank(shopJson)){
            //不存在，直接返回
            return null;
        }
        //3.命中，需要先把JSON反序列化是否过期
        RedisData data = JSONUtil.toBean(shopJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) data.getData(),type);//先获得通用JSONObject，然后再转成shop对象
        LocalDateTime expireTime = data.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回店铺信息
            return r;
        }
        //过期，需要缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //获取互斥锁成功，再次检查Redis里的逻辑过期时间是否过期（因为可能已经被重建了）
            String shopJson1 = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isBlank(shopJson1)){
                return null;
            }
            RedisData data1 = JSONUtil.toBean(shopJson1, RedisData.class);
            R r1 = JSONUtil.toBean((JSONObject) data1.getData(), type);//先获得通用JSONObject，然后再转成shop对象
            LocalDateTime expireTime1 = data1.getExpireTime();
            if(expireTime1.isAfter(LocalDateTime.now())){
                //未过期，直接返回店铺信息
                return r1;
            }
            //检查完成，还是过期，开启独立线程（用线程池去做）
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r2 = dbFallback.apply(id);
                    //重建缓存，调用已有的预热方法
                    this.setWithLogical(key,r2,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    this.unlock(lockKey);
                }
            });

        }
        //返回过期的商铺信息
        return r;
    }
}
