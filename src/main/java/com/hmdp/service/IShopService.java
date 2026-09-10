package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.Shop;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author Fayles
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Shop queryShopById(Long id);

    void updateShop(Shop shop);
}
