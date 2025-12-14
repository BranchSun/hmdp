package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private BlogMapper blogMapper;

    @Override
    public Result isFollow(Long id) {
        //获取当前登录用户的id
        Long userId = UserHolder.getUser().getId();
        //查询是否关注
        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Follow::getFollowUserId, userId);
        queryWrapper.eq(Follow::getUserId, id);
        Follow follow = followMapper.selectOne(queryWrapper);
        return Result.ok(follow != null);
    }


    @Override
    public Result follow(Long id, Boolean isFollow) {
        //获取当前登录用户的id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;

        //如果没关注；执行关注操作
        if (isFollow) {
            //关注操作就是在tb_follow表中添加一条记录
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(userId);
            follow.setCreateTime(LocalDateTime.now());
            int insert = followMapper.insert(follow);
            //将关注该笔记用户的id存到redis的set中，方便后续使用redis的set集合进行取交集的操作实现共同关注
            //为什么要加这个判断，保证数据库与redis的一致性
            if (insert > 0) {
                stringRedisTemplate.opsForSet().add(key, id.toString());
            }
        } else {
            //如果关注了；执行取关操作
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Follow::getUserId, id).eq(Follow::getFollowUserId, userId);
            int delete = followMapper.delete(queryWrapper);
            if (delete > 0) {
                stringRedisTemplate.opsForSet().remove(key, id.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询共同好友
     *
     * @param id 发布笔记的人的id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
        //1.获取当前用户的id
        Long userId = UserHolder.getUser().getId();
        //2.从redis中获取这两个id对应的key的集合的交集
        String key1 = "follows:" + id;
        String key2 = "follows:" + userId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        //不要忘了交集可能为空
        if (intersect == null || intersect.isEmpty()) {
            return Result.fail("没有共同关注的好友!");
        }

        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //3.由于是获取到的交集是id，还要转成userDto
        List<User> users = userMapper.selectBatchIds(ids);
        List<UserDTO> userDTOS = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
