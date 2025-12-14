package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private ShopTypeMapper shopTypeMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 无条件查询所有商铺类型,并按照sort进行排序
     *
     * @return
     */
    @Override
    public Result selectTypeList() {
        //1.查询redis缓存
        String shopTypeStr = stringRedisTemplate.opsForValue().get("shop-type");
        //2.如果缓存中有数据，则直接返回
        if (StrUtil.isNotBlank(shopTypeStr)) {
            List<ShopType> shopTypes = JSONUtil.toList(shopTypeStr, ShopType.class);
            return Result.ok(shopTypes); //  返回对象，而不是字符串
        }
        //3.如果缓存中没有数据，则查询数据库
        LambdaQueryWrapper<ShopType> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.orderByAsc(ShopType::getSort);
        List<ShopType> shopTypes = shopTypeMapper.selectList(queryWrapper);
        //4.如果没有查询到数据，则返回错误信息
        if (shopTypes.isEmpty()) {
            return Result.fail("没有查询到店铺类型！");
        }
        //5.如果查询到数据，则写入redis缓存
        String shopTypesJson = JSONUtil.toJsonStr(shopTypes);
        stringRedisTemplate.opsForValue().set("shop-type", shopTypesJson);

        //6.返回数据
        return Result.ok(shopTypes);
    }
}
