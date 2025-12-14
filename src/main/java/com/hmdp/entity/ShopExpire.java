package com.hmdp.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 功能：
 * 作者：Lizhiyang
 * 功能：2025/10/16 15:18
 */
@Data
public class ShopExpire extends Shop{
    private LocalDateTime expireTime;
}