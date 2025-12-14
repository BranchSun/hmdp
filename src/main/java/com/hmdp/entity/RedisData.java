package com.hmdp.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 功能：封装Shop类和逻辑过期时间
 * 作者：Lizhiyang
 * 功能：2025/10/16 14:38
 */
@Data
public class RedisData {
    private Object data;
    private LocalDateTime expireTime;
}