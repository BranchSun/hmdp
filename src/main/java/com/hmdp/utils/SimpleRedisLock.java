package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 功能：
 * 作者：Lizhiyang
 * 功能：2025/10/17 22:46
 */
public class SimpleRedisLock implements ILock {

    private String name;

    private StringRedisTemplate stringRedisTemplate;

    private static final String UUID_PREFIX = UUID.randomUUID().toString();

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
/*        //key设置为lock:+name
        //这里value设置为当前线程id
        long threadId = Thread.currentThread().getId();
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent("lock:" + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLock);*/

        //key设置为lock:+name
        //value设置为uuid+线程id
        String threadId = UUID_PREFIX + Thread.currentThread().getId();
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent("lock:" + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLock);
    }

    @Override
    public void unLock() {
        //获取写入的线程id
        String threadId = stringRedisTemplate.opsForValue().get("lock:" + name);
        //再获取当前线程id
        String currentThreadId = UUID_PREFIX + Thread.currentThread().getId();
        if (threadId.equals(currentThreadId)) {
            stringRedisTemplate.delete("lock:" + name);
        }
    }
}