package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT
                , Collections.emptyList()
                , voucherId.toString(), userId.toString());
        //2.判断结果是否为0
        int i = result.intValue();
        if(i != 0){
            return Result.fail(i == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2为0，有购买资格,保存下单信息到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        //返回订单id
        return Result.ok(orderId);

    }
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单实现：查询id号和voucherid对应的订单是否存在
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            //用户下过单了
            return Result.fail("用户已经购买过一次！");
        }
        //4.扣减库存
        boolean success = seckillVoucherService.update()//开始创建更新语句
                .setSql("stock = stock - 1")//直接操作数据库，避免丢失更新
                .eq("voucher_id", voucherId).gt("stock",0)//where id = ? and stock = ?
                .update();//执行更新语句 超卖问题修改：更新和库存比较
        if (!success) {
            return Result.fail("库存不足");
        }
        //5.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long id = redisIdWorker.nextId("order");
        Long id1 = UserHolder.getUser().getId();//前面拦截器会存储用户DTO
        voucherOrder.setId(id);
        voucherOrder.setUserId(id1);
        voucherOrder.setVoucherId(voucherId);
        //6.返回订单id给前端
        save(voucherOrder);
        return Result.ok(id);
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //2.判断时间是否在合理范围
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀活动未开始");
//        }
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀活动已经结束");
//        }
//        //3.判断库存是否充足
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //分布式锁
//        //创建锁对象
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock();
////        simpleRedisLock.setName("order:" + userId);
////        simpleRedisLock.setStringRedisTemplate(stringRedisTemplate);
//        //利用Redisson
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean trylock = lock.tryLock();
////        boolean trylock = simpleRedisLock.trylock(1200);
//        if(!trylock){
//            return Result.fail("一个人只允许下一单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
////            simpleRedisLock.unlock();
//            lock.unlock();
//        }
//
//
//        //----------
////        synchronized (userId.toString().intern()){ // 考虑上锁的范围，需要对方法上锁，因为不对方法上锁，在方法结束时，事务没提交时，还是有可能别的线程进入查询，引发线程并发安全。
////            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//    }
}
