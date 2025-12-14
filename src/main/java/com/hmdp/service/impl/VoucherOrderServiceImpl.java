package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@EnableAspectJAutoProxy(exposeProxy = true)
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    @Autowired
    private IVoucherOrderService proxy;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;


    @Override
    public Result snapUpSeckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1.查询优惠券的信息
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(voucherId);
        //2.判断用户抢购的时间点对不对
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }

        LocalDateTime endTime = seckillVoucher.getEndTime();
        if (endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        //3.判断库存是否充足
        //3.1 如果库存不足，则返回错误信息
        Integer stock = seckillVoucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足");
        }
        //3.2 如果库存充足，扣减库存
        /**
         * 线程	查询时数据库 stock	线程计算的 stock - 1	实际执行的 SQL
         * T1	100	                    99	            UPDATE ... SET stock = 99 WHERE stock > 0
         * T2	100	                    99	            UPDATE ... SET stock = 99 WHERE stock > 0
         * T3	100	                    99	            UPDATE ... SET stock = 99 WHERE stock > 0
         *
         * 三个线程都“更新成功”，都把数据库 stock 改成 99。
         * 结果库存只减少 1，而不是 3。
         * 这就是丢失更新。
         */
        /*seckillVoucher.setStock(stock - 1);
        LambdaQueryWrapper<SeckillVoucher> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SeckillVoucher::getVoucherId, voucherId);
        queryWrapper.gt(SeckillVoucher::getStock, 0);
        //seckillVoucherMapper.updateById(seckillVoucher);
        int update = seckillVoucherMapper.update(seckillVoucher, queryWrapper);*/

       /* synchronized (userId.toString().intern()) { // 对每个用户的字符串加锁
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createOrder(voucherId);
        }*/

/*        //手动获取分布式锁
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("oreder:" + userId, stringRedisTemplate);
        boolean isLock = simpleRedisLock.tryLock(5000L);*/
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            return Result.fail("请勿重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }


    /**
     * 创建订单
     */
    @Transactional
    public Result createOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //增加：判断当前用户是否已经抢过这个优惠券
        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(VoucherOrder::getVoucherId, voucherId);
        queryWrapper.eq(VoucherOrder::getUserId, UserHolder.getUser().getId());
        Long count = voucherOrderMapper.selectCount(queryWrapper);
        if (count > 0) {
            return Result.fail("您已经抢过该优惠券！");
        }

        //3.扣减库存
        int update = seckillVoucherMapper.decreaseStock(voucherId);
        if (update < 1) {
            return Result.fail("库存不足,请重试！");
        }

        //4.创建用户抢购秒杀券的订单
        //订单需要三个数据
        //4.1 订单号 ：使用全局Id生成器
        Long orderId = redisIdWorker.nextId("order");
        //4.2 用户id: 从ThreadLocal中获取

        //4.3 优惠券id: voucherId
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrderMapper.insert(voucherOrder);
        //5.返回订单id
        return Result.ok(orderId);
    }
}
