package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
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

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct // 初始化之后开始执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    //判断消息获取是否成功 XREAD GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1")
                            , StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2))
                            , StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if(list == null || list.isEmpty()){
                        continue;
                    }
                    MapRecord<String, Object, Object> entry = list.get(0);
                    Map<Object, Object> value = entry.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //获取成功后下单，并ACK确认
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entry.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    //处理异常消息
                    handlePendingList();
                }
                //ReadOffset 是“位置”，StreamOffset 是“流加位置”，StreamReadOptions 是“读取方式”，Consumer 是“读取者身份”
            }
        }
        private void handlePendingList() {
            while(true){
                try{
                    //获取pendinglist：XREAD GROUP g1 c1 COUNT 1 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1")
                            , StreamReadOptions.empty().count(1)
                            , StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if(list == null || list.isEmpty()){
                        //如果获取失败，说明pendinglist里面没有异常消息，结束循环
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常",e);
                    try {
                        //防止异常太频繁
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }

            }
        }

    }
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while(true){
//                //1.获取队列中的订单信息:如果没有订单信息就会一直阻塞
//                try {
//                    VoucherOrder take = orderTasks.take();
//                    handleVoucherOrder(take);
//                } catch (Exception e) {
//                    log.error("处理订单异常",e);
//                }
//                //
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder take) {
        Long userId = take.getUserId();//不从userHolder里面取得原因，现在不是主线程，是新开的线程
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean b = lock.tryLock();
        if(!b){
            log.error("不允许重复下单");
            return;
        }
        try{
            proxy.createVoucherOrder(take);
        }finally{
            lock.unlock();
        }
    }
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.执行lua脚本
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT
                , Collections.emptyList()
                , voucherId.toString(), userId.toString(),String.valueOf(orderId));
        //2.判断结果是否为0
        int i = result.intValue();
        if(i != 0){
            return Result.fail(i == 1 ? "库存不足" : "不能重复下单");
        }
        //获取代理对象，放到成员变量
        proxy = (IVoucherOrderService)AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);

    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            log.error("用户已经购买一次");
            return;
        }
        boolean update = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if(!update){
            log.error("库存不足！");
            return;
        }
        save(voucherOrder);
        //一人一单实现：查询id号和voucherid对应的订单是否存在
//        Long userId = UserHolder.getUser().getId();
//        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if(count > 0){
//            //用户下过单了
//            return Result.fail("用户已经购买过一次！");
//        }
//        //4.扣减库存
//        boolean success = seckillVoucherService.update()//开始创建更新语句
//                .setSql("stock = stock - 1")//直接操作数据库，避免丢失更新
//                .eq("voucher_id", voucherId).gt("stock",0)//where id = ? and stock = ?
//                .update();//执行更新语句 超卖问题修改：更新和库存比较
//        if (!success) {
//            return Result.fail("库存不足");
//        }
//        //5.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long id = redisIdWorker.nextId("order");
//        Long id1 = UserHolder.getUser().getId();//前面拦截器会存储用户DTO
//        voucherOrder.setId(id);
//        voucherOrder.setUserId(id1);
//        voucherOrder.setVoucherId(voucherId);
//        //6.返回订单id给前端
//        save(voucherOrder);
//        return Result.ok(id);

    }
    //--------------------------------------------------------------------------新增秒杀券优化之前的代码
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
