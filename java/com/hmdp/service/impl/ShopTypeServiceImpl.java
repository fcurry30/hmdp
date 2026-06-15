package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
        String key = RedisConstants.CACHE_SHOPTYPE_KEY;
        //先查Redis，如果存在，返回，不存在，查数据库
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断json是否为空
        if(StrUtil.isNotBlank(json)){
            List<ShopType> list = JSONUtil.toList(json, ShopType.class);
            return Result.ok(list);
        }
        //不存在，查数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if(typeList == null  || typeList.isEmpty()){
            return Result.fail("不存在任何店铺类型");
        }
        //将商铺类型信息缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }
}
