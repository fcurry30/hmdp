-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2. 用户id
local userId = ARGV[2]
-- 1.3 订单id
local orderId = ARGV[3]
--获取数据key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
if (tonumber(redis.call('get', stockKey)) <= 0) then
    --3.2 库存不足
    return 1
end
-- 3.2.判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    --重复下单
    return 2
end
--3.3 扣库存
redis.call('incrby',stockKey,-1)
--3.4 下单
redis.call('sadd',orderKey,userId)
--3.5 发送消息到Stream队列里面 XADD stream.orders * k1 v1 k2 v2 k3 v3
redis.call('XADD','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0