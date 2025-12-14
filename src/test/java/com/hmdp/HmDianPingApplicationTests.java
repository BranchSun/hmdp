package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private IVoucherOrderService voucherOrderService;
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 线程池：模拟并发
    private final ExecutorService executorService = Executors.newFixedThreadPool(50);
    private final ExecutorService executor = Executors.newFixedThreadPool(50);

    @Test
    void testSeckillConcurrency() throws InterruptedException {
        int threadCount = 200;
        CountDownLatch latch = new CountDownLatch(threadCount);
        Long voucherId = 10L;

        for (int i = 0; i < threadCount; i++) {
            int userId = i + 1;
            executorService.submit(() -> {
                try {
                    // 模拟登录用户
                    UserDTO user = new UserDTO();
                    user.setId((long) userId);
                    user.setNickName("user-" + userId);
                    UserHolder.saveUser(user);

                    // 调用业务方法
                    voucherOrderService.snapUpSeckillVoucher(voucherId);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    UserHolder.removeUser();
                    latch.countDown();
                }
            });
        }

        latch.await();
        System.out.println("====== 所有线程执行完毕 ======");
    }


    @Test
    void testOneUserOneOrder() throws InterruptedException {
        Long voucherId = 10L;

        int userCount = 10;      // 模拟10个不同用户
        int threadPerUser = 20;  // 每个用户20次点击
        CountDownLatch latch = new CountDownLatch(userCount * threadPerUser);

        for (int userId = 1; userId <= userCount; userId++) {
            for (int j = 0; j < threadPerUser; j++) {
                int uid = userId;
                executor.submit(() -> {
                    try {
                        // 模拟登录用户
                        UserDTO user = new UserDTO();
                        user.setId((long) uid);
                        user.setNickName("user-" + uid);
                        UserHolder.saveUser(user);

                        // 调用秒杀逻辑
                        Result result = voucherOrderService.snapUpSeckillVoucher(voucherId);

                        if (result.getSuccess()) {
                            System.out.println("✅ 用户 " + uid + " 抢购成功，订单ID：" + result.getData());
                        } else {
                            System.out.println("❌ 用户 " + uid + " 抢购失败：" + result.getErrorMsg());
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        UserHolder.removeUser();
                        latch.countDown();
                    }
                });
            }
        }

        latch.await(); // 等待所有线程执行完毕
        System.out.println("====== 所有线程执行完毕 ======");
    }

    @Test
    void testRedisIdWorker() {
        for (int i = 0; i < 1000; i++) {
            long id = redisIdWorker.nextId("shop");
            System.out.println("id = " + id);
        }
    }

    @Test
    void testGeo() {
        //把数据库里的商铺的地理坐标数据导入到redis中
        //1. 查询店铺信息
        List<Shop> shops = shopMapper.selectList(new LambdaQueryWrapper<>());

        //2. 把店铺信息导入到geo
        //key为店铺类型的id, 然后店铺坐标和member为店铺id
        for (Shop shop : shops) {
            String key = "shop:geo:" + shop.getTypeId();
            stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
        }
    }

}
