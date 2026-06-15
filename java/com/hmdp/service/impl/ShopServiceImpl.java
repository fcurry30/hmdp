package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        //1.缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //2.缓存击穿
        Shop shop = queryWithMutex(id);
        if(shop == null){
            Result.fail("商铺不存在!");
        }
        return Result.ok(shop);
    }

    /**
     * 封装缓存穿透的代码
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        //1.从Redis里查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);//这里采用字符串类型演示
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){//只要"abc"类型的才会返回true
            //存在，利用JSONUTIL把json字符串转成对应的对象
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判空
        if(shopJson != null){  //避免特别复杂的判断
            return null;//返回空值，避免缓存穿透
        }
        //3.不存在，查询数据库
        Shop shop = getById(id);

        //判断是否存在
        if(shop == null){
            //return Result.fail("店铺不存在");
            // 修改，把空值写入redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //写入缓存
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL + RandomUtil.randomInt(1,5), TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 缓存击穿代码：互斥锁
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        if(shopJson != null){
            return null;//表示如果命中的是""
        }
        //未命中
        //1.尝试获取互斥锁
        Shop shop = null;
        try {
            boolean flag = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
            if(!flag){
                //休眠
                Thread.sleep(50);
                return queryWithMutex(id);//递归循环
            }
            //再次检查Redis缓存是否存在
            String shopJson2 = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson2)){
                return JSONUtil.toBean(shopJson2,Shop.class);
            }
            if(shopJson2 != null){
                return null;
            }
            //还是不存在，查询数据库
            shop = getById(id);
            Thread.sleep(200);//模拟重建延时
            if(shop == null){
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            }
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unlock(RedisConstants.LOCK_SHOP_KEY + id);
        }
        return shop;
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        return Result.ok();
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
}
