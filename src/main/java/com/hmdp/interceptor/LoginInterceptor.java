package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 功能：
 * 作者：Lizhiyang
 * 功能：2025/10/14 23:39
 */

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
      /*  // 1.获取session
        HttpSession session = request.getSession();

        //2. 获取用户
        UserDTO userDTO = (UserDTO) session.getAttribute("user");

        //3.判断用户是否存在
        if (userDTO == null) {
            return false;
        }
        //4.把用户存储到ThreadLocal
        UserHolder.saveUser(userDTO);
        //5.放行
        return true;*/

        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        //5.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}