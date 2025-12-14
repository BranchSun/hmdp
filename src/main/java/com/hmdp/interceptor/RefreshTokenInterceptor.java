package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 功能：
 * 作者：Lizhiyang
 * 功能：2025/10/15 1:49
 */
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        //2. 从redis中获取用户的信息
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries("user:" + token);
        //3.判断用户是否存在
        if (userMap.isEmpty()) {
            return true;
        }
        UserDTO userDTO = BeanUtil.mapToBean(userMap, UserDTO.class, false);

        //4.把用户存储到ThreadLocal
        UserHolder.saveUser(userDTO);

        //5.刷新token有效期
        stringRedisTemplate.expire("user:" + token, 30l, TimeUnit.MINUTES);
        //5.放行
        return true;
    }
}