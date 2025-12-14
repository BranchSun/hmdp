package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendcode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //如果手机号不符合规范，返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 2.生成验证码
        String code = RandomUtil.randomNumbers(6);
/*        // 3.保存验证码到session
        session.setAttribute("code", code);*/
        // 3.保存验证码到redis中
        //同时设置验证码的有效期
        stringRedisTemplate.opsForValue().set("code:" + phone, code, 2l, TimeUnit.MINUTES);

        log.info("发送验证码成功：{}", code);

        // 4.返回验证码
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //如果手机号不符合规范，返回错误信息
            return Result.fail("手机号格式错误");
        }
        /*//2.取出session中的验证码
        Object cacheCode = session.getAttribute("code");*/

        //2.从redis中取出验证码
        String cacheCode = stringRedisTemplate.opsForValue().get("code:" + phone);

        String code = loginForm.getCode();
        //3.如果session中没有验证码，或者验证码不一致，返回错误信息
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }

        //4.校验成功,此时判断用户是登录还是注册操作
        //根据手机号取出用户
        //如果能找到用户，则是登录，否则注册
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone, phone);
        User user = userMapper.selectOne(queryWrapper);

        if (user == null) {
            //5.如果用户不存在，则注册, 并且返回新注册的用户信息
            user = createNewUserByPhone(phone);
        }

/*        //6.将用户信息保存到session中
        //这里改进一下不是存用户的全部信息，而是存储用户的部分信息比如说密码就不用返回
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        session.setAttribute("user", userDTO);*/

        //6.将用户信息保存到redis中
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        //6.1 将userDTO转为map对象
//        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString())
        );
        //6.2 生成token作为userDTO的键
        String token = IdUtil.simpleUUID();
        //6.3 保存到redis中
        stringRedisTemplate.opsForHash().putAll("user:" + token, userMap);
        //6.4 设置有效期
        stringRedisTemplate.expire("user:" + token, 30l, TimeUnit.MINUTES);

        //6.4 返回用户token
        return Result.ok(token);
    }

    @Override
    public Result getBloggerInfo(Long bloggerId) {
        User Blogger = userMapper.selectById(bloggerId);
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(Blogger, userDTO);
        return Result.ok(userDTO);
    }

    /**
     * 使用bitmap实现用户签到
     *
     * @return
     */
    @Override
    public Result sign() {
        // 1. 获取用户id（从 UserHolder 中拿）
        Long userId = UserHolder.getUser().getId();

        // 2. 获取当天日期
        LocalDate now = LocalDate.now();
        int dayOfMonth = now.getDayOfMonth(); // 今天是本月第几天

        // 3. 拼接 key：sign:用户id:yyyyMM
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = "sign:" + userId + ":" + keySuffix;

        // 4. 写入 Redis bitmap
        //    offset = dayOfMonth - 1（从 0 开始）
        stringRedisTemplate.opsForValue()
                .setBit(key, dayOfMonth - 1, true);

        // 5. 返回结果
        return Result.ok();
    }

    /**
     * 统计用户最近的连续签到次数
     *
     * @return
     */
    @Override
    public Result signCount() {
        // 1. 拿到 redis 中存储该用户签到的 key
        Long userId = UserHolder.getUser().getId();
        LocalDate now = LocalDate.now();
        int dayOfMonth = now.getDayOfMonth(); // 今天是本月第几天

        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = "sign:" + userId + ":" + keySuffix;

        // 2. 获取该用户本月签到数据（从 1 号到今天，用 bitfield 一次性取出来）
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        // 取 unsigned dayOfMonth 位，从 offset=0 开始
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );

        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            // 说明到今天为止一次都没签
            return Result.ok(0);
        }

        // 3. 统计连续签到次数（从今天往前数，遇到 0 就停）
        int count = 0;
        while ((num & 1) == 1) {  // 判断最低位是否为 1
            count++;
            num >>>= 1;          // 无符号右移一位，继续判断前一天
        }

        // 4. 返回结果
        return Result.ok(count);
    }

    private User createNewUserByPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_" + RandomUtil.randomString(10));

        //往数据库中插入用户
        userMapper.insert(user);

        return user;
    }
}
