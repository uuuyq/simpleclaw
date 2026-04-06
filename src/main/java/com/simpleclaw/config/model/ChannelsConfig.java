package com.simpleclaw.config.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 【渠道配置类】
 *
 * 功能说明：
 * 此类对应config.json中的channels配置节点，支持多个消息渠道：
 * - QQ：QQ机器人
 * - 微信（Weixin）：个人微信（ilinkai API）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChannelsConfig {

    /**
     * QQ渠道配置
     */
    private QQConfig qq;

    /**
     * 微信渠道配置（个人微信模式 - ilinkai API）
     */
    private WeixinConfig weixin;

    /**
     * 【获取QQ配置】
     * 如果配置为空，返回默认配置对象
     */
    public QQConfig getQq() {
        return qq == null ? new QQConfig() : qq;
    }

    /**
     * 【设置QQ配置】
     */
    public void setQq(QQConfig qq) {
        this.qq = qq;
    }

    /**
     * 【获取微信配置】（个人微信模式 - ilinkai API）
     * 如果配置为空，返回默认配置对象
     */
    public WeixinConfig getWeixin() {
        return weixin == null ? new WeixinConfig() : weixin;
    }

    /**
     * 【设置微信配置】（个人微信模式 - ilinkai API）
     */
    public void setWeixin(WeixinConfig weixin) {
        this.weixin = weixin;
    }
}
