package com.simpleclaw.config.model;

import java.util.List;

/**
 * 【个人微信渠道配置类 - 基于ilinkai API】
 * 
 * 功能说明：
 * 此类用于配置个人微信渠道的连接参数，使用微信 ilinkai.weixin.qq.com API。
 * 
 * 特点：
 * - 无需企业微信认证
 * - 无需本地微信客户端
 * - 通过扫码登录获取bot token
 * - HTTP长轮询接收消息
 * 
 * 配置示例（config.json）：
 * {
 *   "channels": {
 *     "weixin": {
 *       "enabled": true,
 *       "baseUrl": "https://ilinkai.weixin.qq.com",
 *       "cdnBaseUrl": "https://novac2c.cdn.weixin.qq.com/c2c",
 *       "token": "",           // 扫码登录后自动填充
 *       "allowFrom": [],       // 白名单，为空则允许所有
 *       "pollTimeout": 35      // 长轮询超时秒数
 *     }
 *   }
 * }
 * 
 * 首次使用：
 * 1. 设置 enabled: true
 * 2. 启动gateway
 * 3. 按提示扫码登录
 * 4. token会自动保存到 ~/.simpleclaw/weixin/account.json
 */
public class WeixinConfig {

    /**
     * 是否启用微信渠道
     */
    private boolean enabled = false;

    /**
     * ilinkai API基础地址
     */
    private String baseUrl = "https://ilinkai.weixin.qq.com";

    /**
     * CDN基础地址（用于媒体文件）
     */
    private String cdnBaseUrl = "https://novac2c.cdn.weixin.qq.com/c2c";

    /**
     * 路由标签（可选）
     */
    private String routeTag = null;

    /**
     * Bot Token（扫码登录后获取）
     * 可以手动设置，或通过扫码登录自动获取
     */
    private String token = "";

    /**
     * 允许的用户列表（白名单）
     * 如果为空，则允许所有用户
     */
    private List<String> allowFrom;

    /**
     * 状态保存目录
     * 默认：~/.simpleclaw/weixin/
     */
    private String stateDir = "";

    /**
     * 长轮询超时时间（秒）
     */
    private int pollTimeout = 35;

    // ========== Getter 和 Setter ==========

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCdnBaseUrl() {
        return cdnBaseUrl;
    }

    public void setCdnBaseUrl(String cdnBaseUrl) {
        this.cdnBaseUrl = cdnBaseUrl;
    }

    public String getRouteTag() {
        return routeTag;
    }

    public void setRouteTag(String routeTag) {
        this.routeTag = routeTag;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public List<String> getAllowFrom() {
        return allowFrom;
    }

    public void setAllowFrom(List<String> allowFrom) {
        this.allowFrom = allowFrom;
    }

    public String getStateDir() {
        return stateDir;
    }

    public void setStateDir(String stateDir) {
        this.stateDir = stateDir;
    }

    public int getPollTimeout() {
        return pollTimeout;
    }

    public void setPollTimeout(int pollTimeout) {
        this.pollTimeout = pollTimeout;
    }

    // ========== 辅助方法 ==========

    /**
     * 【检查是否配置了白名单】
     */
    public boolean hasAllowList() {
        return allowFrom != null && !allowFrom.isEmpty();
    }

    /**
     * 【检查用户是否在白名单中】
     */
    public boolean isUserAllowed(String userId) {
        if (!hasAllowList()) {
            return true; // 没有白名单，允许所有用户
        }
        return allowFrom.contains(userId);
    }
}
