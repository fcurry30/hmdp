package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;
import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;

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
    @Resource
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Override
    public Result queryById(Long id) {
        //1.缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //2.缓存击穿
        //Shop shop = queryWithMutex(id);
        //Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("商铺不存在!");
        }
        return Result.ok(shop);
    }



    /**
     * 基于逻辑过期解决缓存击穿问题
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        //1.从Redis里查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);//这里采用字符串类型演示
        //2.判断是否存在
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //3.命中，需要先把JSON反序列化是否过期
        RedisData data = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) data.getData(), Shop.class);//先获得通用JSONObject，然后再转成shop对象
        LocalDateTime expireTime = data.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回店铺信息
            return shop;
        }
        //过期，需要缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //获取互斥锁成功，再次检查Redis里的逻辑过期时间是否过期（因为可能已经被重建了）
            String shopJson1 = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            if(StrUtil.isBlank(shopJson1)){
                return null;
            }
            RedisData data1 = JSONUtil.toBean(shopJson1, RedisData.class);
            Shop shop1 = JSONUtil.toBean((JSONObject) data1.getData(), Shop.class);//先获得通用JSONObject，然后再转成shop对象
            LocalDateTime expireTime1 = data1.getExpireTime();
            if(expireTime1.isAfter(LocalDateTime.now())){
                //未过期，直接返回店铺信息
                return shop1;
            }
            //检查完成，还是过期，开启独立线程（用线程池去做）
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存，调用已有的预热方法
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    this.unlock(lockKey);
                }
            });

        }
        //返回过期的商铺信息
        return shop;
    }
    /**
     * 封装缓存穿透的代码
     * @param id
     * @return
     */
//    public Shop queryWithPassThrough(Long id){
//        //1.从Redis里查询缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);//这里采用字符串类型演示
//        //2.判断是否存在
//        if(StrUtil.isNotBlank(shopJson)){//只要"abc"类型的才会返回true
//            //存在，利用JSONUTIL把json字符串转成对应的对象
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        //判空
//        if(shopJson != null){  //避免特别复杂的判断
//            return null;//返回空值，避免缓存穿透
//        }
//        //3.不存在，查询数据库
//        Shop shop = getById(id);
//
//        //判断是否存在
//        if(shop == null){
//            //return Result.fail("店铺不存在");
//            // 修改，把空值写入redis
//            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return null;
//        }
//        //写入缓存
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL + RandomUtil.randomInt(1,5), TimeUnit.MINUTES);
//        return shop;
//    }

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
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据坐标查询
        if(x == null || y == null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, DEFAULT_PAGE_SIZE));
            // 就是告诉 MyBatis-Plus：我要查第 current 页，每页 size 条；底层会自动换算成 SQL 的 LIMIT (current - 1) * size, size，从而跳过前current - 1) * size页
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int from = (current - 1) * DEFAULT_PAGE_SIZE;
        int end = current * DEFAULT_PAGE_SIZE;
        //3.查询redis，按照距离排序、分页：shopId和distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key
                , GeoReference.fromCoordinate(x, y)
                , new Distance(5000)
                , RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        //key,经纬度确定的参照点，距离参照点的距离，最后是可设置的参数，例如include表示返回需要带上距离，limit表示第一条开始的多少条。
        if(results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from ){
            //没有下一页了
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        //4.解析出id，查询出店铺的信息，分页后返回给前端
        list.stream().skip(from).forEach(result -> {    // 在skip这一步时，前面是有数据的，但可能被跳过了。
            //获取店铺id
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            //获取店铺距离
            Distance distance = result.getDistance();
            distanceMap.put(shopId,distance);
        });//获取两个新的集合后，查询出shoplist，把距离也加进去
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
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

    /**
     * 存储数据至redis
     * @param id
     * @param expireSeconds
     */
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.获取商铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //存入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }
}
