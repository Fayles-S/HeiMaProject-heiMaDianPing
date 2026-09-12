package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Fayles
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

    @PostConstruct
    private void init()
    {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private class VoucherOrderHandler implements Runnable
    {
        @Override
        public void run()
        {
            while(true)
            {
                try
                {
                    VoucherOrder voucherOrder = orderTasks.take();
                    voucherOrderHandle(voucherOrder);
                }
                catch (Exception e)
                {
                    log.error("处理订单异常", e);
                }
            }
        }
    }


    private IVoucherOrderService proxy;
    private void voucherOrderHandle(VoucherOrder voucherOrder)
    {
        Long userId = voucherOrder.getUserId();
        //SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        if(!lock.tryLock())
        {
            log.error("一人只能下一单");
            return;
        }
        try
        {
            proxy.createVoucherOrder(voucherOrder);
        }
        finally
        {
            lock.unlock();
        }
    }


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static
    {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) 
    {
        //执行lua脚本获取结果
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute
                (
                        SECKILL_SCRIPT,
                        Collections.emptyList(),
                        StrUtil.toString(voucherId), StrUtil.toString(userId)
                );
        if (result != 0L)
        {
            return result == 1 ? Result.ok("库存不足") : Result.ok("不允许重复下单");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        // proxy 必须在入队之前赋值：BlockingQueue 的 add/take 建立 happens-before，
        // 保证消费者线程能看到 proxy；顺序反了消费者可能读到 null 导致丢单
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        orderTasks.add(voucherOrder);
        return Result.ok(orderId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder)
    {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0)
        {
            log.error("下单失败，一人仅可下一单");
            return;
        }
        boolean success = seckillVoucherService.update()
                .setSql("Stock = Stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success)
        {
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId)
    {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now()))
        {
            return Result.fail("活动未开始");
        }
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now()))
        {
            return Result.fail("活动已结束");
        }
        if(seckillVoucher.getStock() < 1)
        {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        //SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        if(!lock.tryLock())
        {
            return Result.fail("一人只能下一单");
        }
        try
        {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
        finally
        {
            lock.unlock();
        }
    }*/
}
