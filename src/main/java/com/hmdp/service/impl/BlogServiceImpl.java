package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private BlogMapper blogMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private FollowMapper followMapper;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = blogMapper.selectById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        setBlogIsLike(id);
        return Result.ok(blog);
    }

    public void setBlogIsLike(Long id) {
        //1.查询当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        //2.判断当前用户是否已经点赞 怎么判断？就是用户的id是否在点这个博客的集合中
        String key = "blog:like:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        Blog blog = blogMapper.selectById(id);
        blog.setIsLike(score != null);
    }

    /**
     * 实现点赞排行榜，类似于微信的点赞可以看到最早点赞的一些用户
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikesById(Long id) {
        /*//1.根据博客笔记id查询最早点赞的五个用户的id
        String key = "blog:like:" + id;
        Set<String> strings = stringRedisTemplate.opsForZSet().reverseRange(key, 0, 4);
        if (strings == null || strings.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> userIds = strings.stream().map(Long::valueOf).collect(Collectors.toList());
        //2.根据用户id查询用户信息
        List<UserDTO> userDTOS = userMapper.selectBatchIds(userIds).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());*/
        String key = "blog:like:" + id;
        Set<String> strings = stringRedisTemplate.opsForZSet().reverseRange(key, 0, 4);
        if (strings == null || strings.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> userIds = strings.stream().map(Long::valueOf).collect(Collectors.toList());
        // 把 [5,1] 转成 "5,1" 方便拼到 FIELD 里
        String orderSql = userIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        // 构造 LambdaQueryWrapper
        LambdaQueryWrapper<User> wrapper = Wrappers.<User>lambdaQuery()
                .in(User::getId, userIds)
                // 这里用 last 拼接原生 SQL：ORDER BY FIELD(id, 5,1)
                .last("ORDER BY FIELD(id, " + orderSql + ")");

        // 查询 + 映射成 DTO
        List<UserDTO> userDTOS = userMapper.selectList(wrapper).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());


        return Result.ok(userDTOS);
    }

    @Override
    public Result queryBlogsByIdAndPage(Long userId, Integer current) {
        // 1. 构造分页参数：current 当前页，5 每页条数
        Page<Blog> page = new Page<>(current, 5);

        // 2. 构造条件
        LambdaQueryWrapper<Blog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Blog::getUserId, userId)
                .orderByDesc(Blog::getCreateTime);   // 可选：按时间倒序

        // 3. 执行分页查询
        IPage<Blog> resultPage = blogMapper.selectPage(page, wrapper);

        // 4. 封装返回
        return Result.ok(resultPage.getRecords());
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        blog.setUserId(userId);
        // 保存探店博文
        boolean isSave = save(blog);
        if (!isSave) {
            return Result.fail("笔记发送失败!");
        }
        //对每个粉丝进行推送笔记，这里只推送笔记的id
        //先获取当前用户的所有粉丝
        List<Follow> follows = followMapper.selectList(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId));
        //遍历每个粉丝，将笔记id推送到每个粉丝的收件箱
        for (Follow follow : follows) {
            Long followUserId = follow.getFollowUserId();
            //每一个key用粉丝的id做键表示这个粉丝独一无二的收件箱
            String key = "feed:" + followUserId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    /* *//**
     * 实现博客的点赞
     *
     * @param id
     * @return
     *//*
    @Override
    public Result likeBlog(Long id) {
        //1.查询当前登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        //2.判断当前用户是否已经点赞 怎么判断？就是用户的id是否在点这个博客的集合中
        String key = "blog:like:" + id;
        Boolean isLike = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Blog blog = blogMapper.selectById(id);

        if (BooleanUtil.isFalse(isLike)) {
            //3.如果未点赞，新增点赞记录
            //3.1 Redis新增点赞记录
            stringRedisTemplate.opsForSet().add(key, userId.toString());
            //3.2 数据库中博客点赞数量+1
            blog.setLiked(blog.getLiked() + 1);
            blogMapper.updateById(blog);
        } else {
            //4.如果已点赞，删除点赞记录
            //4.1 Redis删除点赞记录
            stringRedisTemplate.opsForSet().remove(key, userId.toString());
            //4.2 数据库中博客点赞数量-1
            blog.setLiked(blog.getLiked() - 1);
            blogMapper.updateById(blog);
        }
        return Result.ok();
    }*/

    /**
     * 实现博客的点赞(SortedSet)
     *
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //1.查询当前登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        //2.判断当前用户是否已经点赞 怎么判断？就是用户的id是否在点这个博客的集合中
        String key = "blog:like:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        Blog blog = blogMapper.selectById(id);

        if (score == null) {
            //3.如果未点赞，新增点赞记录
            //3.1 Redis新增点赞记录
            //因为需要排序，所以选择redis的sortedset结构
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            //3.2 数据库中博客点赞数量+1
            blog.setLiked(blog.getLiked() + 1);
            blogMapper.updateById(blog);
        } else {
            //4.如果已点赞，删除点赞记录
            //4.1 Redis删除点赞记录
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            //4.2 数据库中博客点赞数量-1
            blog.setLiked(blog.getLiked() - 1);
            blogMapper.updateById(blog);
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogOfFollow(Long max, Long offset) {
        //根据这个redis的命令语句来写，使用的是sortedSet来存取的博客笔记的id
        //ZREVRANGEBYSCORE key 最大值 -inf(最小值) WITHSCORES LIMIT 0或上一次最小值的重复个数(offset) 10(size)
        //分数使用时间戳来代表，越后发布的分数越大，越最早被看到

        //1.先拿到redis中的该用户的收件箱中的全部博客id
        String key = "feed:" + UserHolder.getUser().getId();
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.fail("没有更多笔记了!");
        }
        //2.根据博客id查询博客信息
        //2.1 解析出博客的id
        List<Long> ids = typedTuples.stream().map(ZSetOperations.TypedTuple::getValue).map(Long::valueOf).collect(Collectors.toList());
        //2.2 根据id查询博客信息
        // 把 [5,1] 转成 "5,1" 方便拼到 FIELD 里
        String orderSql = ids.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        // 构造 LambdaQueryWrapper
        LambdaQueryWrapper<Blog> wrapper = Wrappers.<Blog>lambdaQuery()
                .in(Blog::getId, ids)
                // 这里用 last 拼接原生 SQL：ORDER BY FIELD(id, 5,1)
                // 保持排序顺序不变
                .last("ORDER BY FIELD(id, " + orderSql + ")");

        // 查询
        List<Blog> blogs = blogMapper.selectList(wrapper);
        //3.注意：还要返回lastId(也就是minTime)和offset,这里定义另一个ScrollResult类来返回
        Double minTime = 0.0;
        int ot = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            Double score = typedTuple.getScore();
            if (Objects.equals(score, minTime)) {
                ot++;
            } else {
                minTime = score;
                ot = 1;
            }
        }
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime.longValue());
        scrollResult.setOffset(ot);
        return Result.ok(scrollResult);
    }
}
