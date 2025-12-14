package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.ShopExpire;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ShopMapper shopMapper;

    // 用于重建缓存的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 为根据id查询商铺信息添加redis缓存
     *
     * @param id
     * @return
     */
   /* @Override
    public Result selectShopById(Long id) {
        //1.查询redis缓存
        Map<Object, Object> shopMap = stringRedisTemplate.opsForHash().entries("shop:" + id);

        //2.如果缓存中有店铺信息，则返回
        if (!shopMap.isEmpty()) {
            Shop shop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
            return Result.ok(shop);
        }

        //增加判断：这时候肯定是没有店铺信息或者有key但是店铺信息为空
        Boolean flag = stringRedisTemplate.hasKey("shop:" + id);
        if (Boolean.TRUE.equals( flag)) {
            return Result.ok("店铺信息不存在！");
        }

        //这个时候shopMap肯定没有店铺信息
        //3.如果缓存中没有店铺信息，则查询数据库
        Shop shop = shopMapper.selectById(id);
        //4.如果在数据库中查不到，就返回错误信息
        if (shop == null) {
            stringRedisTemplate.opsForHash().putAll("shop:" + id, new HashMap<>());
            stringRedisTemplate.expire("shop:" + id, 2l, TimeUnit.MINUTES);

            return Result.fail("店铺不存在");
        }
        //5.如果数据库中查到了，则写入redis缓存
        Map<String, Object> cacheShopMap = BeanUtil.beanToMap(shop, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString())
        );
        stringRedisTemplate.opsForHash().putAll("shop:" + id, cacheShopMap);
        //补充：设置有效期
        stringRedisTemplate.expire("shop:" + id, 30l, TimeUnit.MINUTES);
        //6.返回信息
        return Result.ok(shop);
    }*/
    @Override
    public Result selectShopById(Long id) {
        //return selectShopByIdWithPassThrough(id);
        //return selectShopByIdWithPunctureByLock(id);
        return selectShopByIdWithPunctureByLogicExpire(id);
    }

    @Override
    @Transactional
    public Result updateShopById(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }

        //1.更新数据库
        shopMapper.updateById(shop);
        //2.删除redis缓存
        stringRedisTemplate.delete("shop:" + id);
        //3.返回信息
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x==null || y==null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }else{
            // 根据类型和距离排序分页查询
            // 2. 有经纬度：按距离排序 + 分页查询
            int pageSize = SystemConstants.DEFAULT_PAGE_SIZE;
            int from = (current - 1) * pageSize;
            int end = current * pageSize;   // Redis 里查到第 end 个为止，再手动截取当前页

            String key = "shop:geo:" + typeId;

            // 2.1 在 Redis 中按距离升序查询附近店铺（这里写 5000 表示 5km，可按需求调）
            GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                    stringRedisTemplate.opsForGeo().radius(
                            key,
                            new Circle(new Point(x, y), new Distance(5000)),
                            RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                    .includeDistance()      // 把距离一并查出来
                                    .limit(end)             // 最多拿到第 end 条
                                    .sortAscending()        // 按距离从近到远排序
                    );

            if (results == null) {
                return Result.ok(Collections.emptyList());
            }

            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
            if (list.size() <= from) {
                // 连这一页的起始下标都不到，说明这一页没有数据
                return Result.ok(Collections.emptyList());
            }

            // 2.2 截取当前页的数据，取出店铺 id 和距离
            List<Long> ids = new ArrayList<>(pageSize);
            Map<String, Distance> distanceMap = new HashMap<>();   // shopId -> distance

            list.stream()
                    .skip(from)            // 跳过前面 (current-1) 页
                    .limit(pageSize)       // 只要当前页大小
                    .forEach(r -> {
                        String shopIdStr = r.getContent().getName();
                        ids.add(Long.valueOf(shopIdStr));
                        distanceMap.put(shopIdStr, r.getDistance());
                    });

            if (ids.isEmpty()) {
                return Result.ok(Collections.emptyList());
            }

            // 2.3 根据 id 批量查询店铺信息，并保持顺序
            // 用 ORDER BY FIELD(id, ...) 保证顺序和 ids 一致
            String idStr = StrUtil.join(",", ids);
            List<Shop> shops = query()
                    .in("id", ids)
                    .last("ORDER BY FIELD(id," + idStr + ")")
                    .list();

            // 2.4 填充距离到每个 Shop（前端要用）
            for (Shop shop : shops) {
                Distance distance = distanceMap.get(shop.getId().toString());
                if (distance != null) {
                    shop.setDistance(distance.getValue()); // km 为单位
                }
            }
            return Result.ok(shops);
        }
    }


    /**
     * 防穿透的缓存解决方法
     */
    private Result selectShopByIdWithPassThrough(Long id) {
        String key = "shop:" + id;
        // 1. 查询缓存
        Map<Object, Object> shopMap = stringRedisTemplate.opsForHash().entries(key);
        if (!shopMap.isEmpty()) {
            // 判断是否是空标记
            if (shopMap.containsKey("isNull")) {
                return Result.fail("店铺不存在");
            }

            // 正常缓存
            Shop shop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
            return Result.ok(shop);
        }
        // 2. 查询数据库
        Shop shop = shopMapper.selectById(id);
        if (shop == null) {
            // 缓存空对象（防止穿透）
            // 认为添加一个isnull字段，以便存入缓存
            stringRedisTemplate.opsForHash().put("shop:" + id, "isNull", "true");
            stringRedisTemplate.expire(key, 2l, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        // 3. 写入缓存
        Map<String, Object> cacheShopMap = BeanUtil.beanToMap(shop, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((f, v) -> v == null ? null : v.toString())
        );
        stringRedisTemplate.opsForHash().putAll(key, cacheShopMap);
        stringRedisTemplate.expire(key, 30, TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    /**
     * 防击穿的缓存解决方法(使用互斥锁)
     */
    private Result selectShopByIdWithPunctureByLock(Long id) {
        String key = "shop:" + id;
        String lockKey = "lock:" + id;
        while (true) {
            // 1. 查询缓存
            Map<Object, Object> shopMap = stringRedisTemplate.opsForHash().entries(key);
            if (!shopMap.isEmpty()) {
                // 判断是否是空标记
                if (shopMap.containsKey("isNull")) {
                    return Result.fail("店铺不存在");
                }

                // 正常缓存
                Shop shop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
                return Result.ok(shop);
            }
            // 缓存未命中，进行访问数据库进行缓存重建
            // 2.1 获取锁
            Boolean isLock = getLock(lockKey);
            //2.2 如果获取锁失败，需要等待一段时间然后回到第一步查询缓存
            if (Boolean.FALSE.equals(isLock)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    log.error("Thread interrupted while waiting for lock", e);
                    throw new RuntimeException(e);
                }
            } else {
                //获取锁成功，跳出循环
                break;
            }
        }
        //2.3 获取锁成功，进行缓存重建
        // 2. 查询数据库
        Shop shop;
        try {
            /**
             * 当某个线程获取锁成功时，它确实打算重建缓存，但在它等待获取锁的过程中，可能：
             * 另一个线程（先一步拿到锁）已经查完数据库、重建了缓存；
             * 当前线程再查数据库 → 就会造成重复查询数据库 + 重建缓存；
             */
            // 再查一次缓存（双重检查）
            Map<Object, Object> doubleCheckMap = stringRedisTemplate.opsForHash().entries(key);
            if (!doubleCheckMap.isEmpty()) {
                if (doubleCheckMap.containsKey("isNull")) {
                    return Result.fail("店铺不存在");
                }
                Shop cachedShop = BeanUtil.fillBeanWithMap(doubleCheckMap, new Shop(), false);
                return Result.ok(cachedShop);
            }

            shop = shopMapper.selectById(id);
            //模拟缓存重建的延迟
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.error("Thread interrupted while waiting for lock", e);
                throw new RuntimeException(e);
            }
            if (shop == null) {
                // 缓存空对象（防止穿透）
                // 认为添加一个isnull字段，以便存入缓存
                stringRedisTemplate.opsForHash().put("shop:" + id, "isNull", "true");
                stringRedisTemplate.expire(key, 2l, TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }
            // 3. 写入缓存
            Map<String, Object> cacheShopMap = BeanUtil.beanToMap(shop, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((f, v) -> v == null ? null : v.toString())
            );
            stringRedisTemplate.opsForHash().putAll(key, cacheShopMap);
            stringRedisTemplate.expire(key, 30, TimeUnit.MINUTES);
        } finally {
            //锁一定要释放，避免死锁，所以使用finally
            unLock(lockKey);
        }
        return Result.ok(shop);
    }

    /**
     * 防击穿的缓存解决方法(使用逻辑过期)
     */
    private Result selectShopByIdWithPunctureByLogicExpire(Long id) {
        String key = "shop:" + id;
        String lockKey = "lock:" + id;
        Shop shop = null;
        // 1. 查询缓存
        Map<Object, Object> shopMap = stringRedisTemplate.opsForHash().entries(key);
        if (!shopMap.isEmpty()) {
            // 判断是否是空标记
            if (shopMap.containsKey("isNull")) {
                return Result.fail("店铺不存在");
            }

            // 如果缓存中不为空
            // 原来是直接返回数据，但是现在需要判断数据是否过期
            // 所以需要一个封装shop类的信息和过期时间的信息的类,为RedisData
            // 所以写入redis从写Shop变为写RedisData
            /*Shop shop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
            return Result.ok(shop);*/
            // 取出逻辑过期时间并判断
            ShopExpire shopExpire = BeanUtil.fillBeanWithMap(shopMap, new ShopExpire(), false);
            LocalDateTime expireTime = shopExpire.getExpireTime();
            // 把shopExpire转为shop
            shop = shopExpire2Shop(shopExpire);
            // 如果没过期，则直接返回缓存
            if (expireTime.isAfter(LocalDateTime.now())) {
                return Result.ok(shop);
            }
        }
        // 如果过期了，则进行缓存重建
        // 2.1 获取锁
        Boolean isLock = getLock(lockKey);
        //2.2 如果获取锁失败，说明有线程正在缓存构建，直接返回过期数据
        if (Boolean.FALSE.equals(isLock)) {
            return Result.ok(shop);
        }
        //2.3 获取锁成功，开启新线程进行缓存重建
        try {
            /**
             * 当某个线程获取锁成功时，它确实打算重建缓存，但在它等待获取锁的过程中，可能：
             * 另一个线程（先一步拿到锁）已经查完数据库、重建了缓存；
             * 当前线程再查数据库 → 就会造成重复查询数据库 + 重建缓存；
             */
            // 再查一次缓存（双重检查）
            Map<Object, Object> doubleCheckMap = stringRedisTemplate.opsForHash().entries(key);
            if (!doubleCheckMap.isEmpty()) {
                // 判断是否是空标记
                if (doubleCheckMap.containsKey("isNull")) {
                    return Result.fail("店铺不存在");
                }
                // 取出逻辑过期时间并判断
                RedisData shopExpire = BeanUtil.fillBeanWithMap(shopMap, new RedisData(), false);
                LocalDateTime expireTime = shopExpire.getExpireTime();
                shop = (Shop) shopExpire.getData();
                // 如果没过期，则直接返回缓存
                if (expireTime.isAfter(LocalDateTime.now())) {
                    return Result.ok(shop);
                }
            }

            //开启新线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 1. 查询数据库
                    Shop newShop = shopMapper.selectById(id);
                    if (newShop == null) {
                        stringRedisTemplate.opsForHash().put(key, "isNull", "true");
                        stringRedisTemplate.expire(key, 2L, TimeUnit.MINUTES);
                        return;
                    }

                    // 2. 封装逻辑过期数据
                    ShopExpire shopExpire = shop2ShopExpire(newShop);

                    // 3. 写入 Redis
                    Map<String, Object> cacheMap = BeanUtil.beanToMap(shopExpire, new HashMap<>(),
                            CopyOptions.create()
                                    .setIgnoreNullValue(true)
                                    .setFieldValueEditor((f, v) -> v == null ? null : v.toString())
                    );
                    stringRedisTemplate.opsForHash().putAll(key, cacheMap);
                    stringRedisTemplate.expire(key, 30, TimeUnit.MINUTES);

                    log.info("缓存重建成功：shop:{}", id);
                } catch (Exception e) {
                    log.error("缓存重建失败：shop:{}", id, e);
                } finally {
                    // 一定要释放锁
                    unLock(lockKey);
                }
            });
        } catch (Exception e) {
            log.error("逻辑过期缓存重建异常", e);
        }
        return Result.ok(shop);
    }

    private Shop shopExpire2Shop(ShopExpire shopExpire) {
        Shop shop = new Shop();
        shop.setId(shopExpire.getId());
        shop.setName(shopExpire.getName());
        shop.setTypeId(shopExpire.getTypeId());
        shop.setImages(shopExpire.getImages());
        shop.setArea(shopExpire.getArea());
        shop.setAddress(shopExpire.getAddress());
        shop.setX(shopExpire.getX());
        shop.setY(shopExpire.getY());
        shop.setAvgPrice(shopExpire.getAvgPrice());
        shop.setSold(shopExpire.getSold());
        shop.setComments(shopExpire.getComments());
        shop.setScore(shopExpire.getScore());
        shop.setOpenHours(shopExpire.getOpenHours());
        shop.setCreateTime(shopExpire.getCreateTime());
        shop.setUpdateTime(shopExpire.getUpdateTime());
        shop.setDistance(shopExpire.getDistance());
        return shop;
    }

    private ShopExpire shop2ShopExpire(Shop newShop) {
        ShopExpire shopExpire = new ShopExpire();
        shopExpire.setId(newShop.getId());
        shopExpire.setName(newShop.getName());
        shopExpire.setTypeId(newShop.getTypeId());
        shopExpire.setImages(newShop.getImages());
        shopExpire.setArea(newShop.getArea());
        shopExpire.setAddress(newShop.getAddress());
        shopExpire.setX(newShop.getX());
        shopExpire.setY(newShop.getY());
        shopExpire.setAvgPrice(newShop.getAvgPrice());
        shopExpire.setSold(newShop.getSold());
        shopExpire.setComments(newShop.getComments());
        shopExpire.setScore(newShop.getScore());
        shopExpire.setOpenHours(newShop.getOpenHours());
        shopExpire.setCreateTime(newShop.getCreateTime());
        shopExpire.setUpdateTime(newShop.getUpdateTime());
        shopExpire.setExpireTime(LocalDateTime.now().plusMinutes(30L));
        return shopExpire;
    }

    /**
     * 获取锁
     */
    private Boolean getLock(String key) {
        //锁要设置一个有效期，防止死锁
        //Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1");
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        BooleanUtil.isFalse(flag);
        if (Boolean.TRUE.equals(flag)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * 释放锁
     */
    private void unLock(String key) {
        //删除这个键值对
        stringRedisTemplate.delete(key);
    }
}
