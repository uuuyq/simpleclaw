package com.simpleclaw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.simpleclaw.bus.MessageBus;
import com.simpleclaw.bus.OutboundMessage;
import com.simpleclaw.config.model.GatewayConfig;
import com.simpleclaw.config.model.WeixinConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【个人微信渠道实现类 - 基于ilinkai.weixin.qq.com API】
 * 
 * 功能说明：
 * 此类实现了个人微信的消息收发功能，使用微信官方提供的 ilinkai API：
 * - 无需企业微信认证
 * - 无需本地微信客户端
 * - 通过HTTP长轮询接收消息
 * - 通过扫码登录获取bot token
 * 
 * 工作流程：
 * 
 * 【扫码登录流程】
 * 1. 调用 get_bot_qrcode 获取登录二维码
 * 2. 用户使用手机微信扫描二维码
 * 3. 轮询 get_qrcode_status 检查登录状态
 * 4. 登录成功后获取 bot_token
 * 5. 保存token用于后续API调用
 * 
 * 【接收消息流程】
 * 1. 使用 bot_token 调用 getupdates 长轮询接口
 * 2. 解析返回的消息列表
 * 3. 处理文本、图片、语音、文件等消息类型
 * 4. 构造InboundMessage并发布到MessageBus
 * 
 * 【发送消息流程】
 * 1. 构造微信消息格式
 * 2. 调用 sendmessage 接口发送
 * 3. 支持文本、图片、文件等多种类型
 * 
 * API文档参考：微信 ilinkai 开放接口
 */
@Slf4j
public class WeixinChannel extends BaseChannel {

    // ========== 协议常量 ==========
    
    /** 消息项类型 */
    private static final int ITEM_TEXT = 1;
    private static final int ITEM_IMAGE = 2;
    private static final int ITEM_VOICE = 3;
    private static final int ITEM_FILE = 4;
    private static final int ITEM_VIDEO = 5;
    
    /** 消息类型 */
    private static final int MESSAGE_TYPE_USER = 1;
    private static final int MESSAGE_TYPE_BOT = 2;
    
    /** 消息状态 */
    private static final int MESSAGE_STATE_FINISH = 2;
    
    /** 会话过期错误码 */
    private static final int ERRCODE_SESSION_EXPIRED = -14;
    
    /** 最大连续失败次数 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    
    /** 重试延迟（秒） */
    private static final int RETRY_DELAY_S = 2;
    private static final int BACKOFF_DELAY_S = 30;
    
    /** 默认长轮询超时（秒） */
    private static final int DEFAULT_LONG_POLL_TIMEOUT_S = 35;
    
    /** 最大消息长度 */
    private static final int WEIXIN_MAX_MESSAGE_LEN = 4000;
    
    /** 渠道版本 */
    private static final String WEIXIN_CHANNEL_VERSION = "1.0.3";

    // 【微信登录指引】
    private static final String WEIXIN_LOGIN_INSTRUCTIONS =
            "【微信扫码登录】\n\n" +
            "请按以下步骤操作：\n" +
            "1. 打开手机微信\n" +
            "2. 点击右上角 + 号 -> 扫一扫\n" +
            "3. 扫描下方二维码\n" +
            "4. 在手机上确认登录\n";

    // 【微信登录成功提示模板】
    private static final String WEIXIN_LOGIN_SUCCESS_TEMPLATE =
            "\n【登录成功】\n\n" +
            "Bot ID: %s\n" +
            "User ID: %s\n\n" +
            "请将以下配置添加到 config.json:\n" +
            "\"token\": \"%s...\"\n";

    // ========== 工具 ==========
    
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    
    // ========== 配置 ==========
    
    private final WeixinConfig config;
    
    // ========== 状态 ==========
    
    private String token = "";
    private String getUpdatesBuf = "";
    private final Map<String, String> contextTokens = new ConcurrentHashMap<>();
    
    // 使用LinkedHashMap实现LRU缓存，限制消息去重缓存大小
    private final Map<String, Boolean> processedIdsMap = new LinkedHashMap<String, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 1000;
        }
    };
    private final Set<String> processedIds = Collections.newSetFromMap(processedIdsMap);
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Future<?> pollTask;
    
    private long sessionPauseUntil = 0;
    private int nextPollTimeoutS = DEFAULT_LONG_POLL_TIMEOUT_S;
    
    /**
     * 构造函数
     */
    public WeixinChannel(WeixinConfig config, GatewayConfig gatewayConfig, MessageBus bus) {
        super("weixin", bus);
        this.config = config != null ? config : new WeixinConfig();
    }
    
    // ========== 生命周期 ==========
    
    @Override
    public void start() {
        if (running.getAndSet(true)) {
            return;
        }
        
//        System.out.println("[Weixin] 启动微信渠道...");
        
        // 加载状态
        loadState();
        
        // 如果没有token，执行扫码登录
        if (token == null || token.isEmpty()) {
            log.info("未找到登录凭证，需要扫码登录");
            if (!performQRLogin()) {
                log.error("登录失败，渠道启动失败");
                running.set(false);
                return;
            }
        }
        
        // 启动消息轮询
        startPolling();
        
        log.info("微信渠道已启动");
    }
    
    @Override
    public void stop() {
        running.set(false);
        
        // 先保存状态，再关闭调度器
        saveState();
        
        if (pollTask != null) {
            pollTask.cancel(false);
        }
        
        scheduler.shutdown();
        
        log.info("微信渠道已停止");
    }
    
    // ========== 扫码登录 ==========
    
    /**
     * 【执行扫码登录】
     */
    private boolean performQRLogin() {
        try {
            log.info("\n{}", createSeparatorLine("=", 60));
            log.info(WEIXIN_LOGIN_INSTRUCTIONS);
            log.info("{}", createSeparatorLine("=", 60));
            
            // 获取二维码
            JsonNode qrData = fetchQRCode();
            if (qrData == null) {
                log.error("获取二维码失败");
                return false;
            }
            
            String qrcodeId = qrData.get("qrcode").asText();
            String qrcodeContent = qrData.has("qrcode_img_content") 
                    ? qrData.get("qrcode_img_content").asText() 
                    : qrcodeId;
            
            // 显示二维码
            displayQRCode(qrcodeContent);
            
            // 等待扫码
            log.info("等待扫码...（按Enter键取消）\n");
            
            Scanner scanner = new Scanner(System.in);
            long startTime = System.currentTimeMillis();
            long timeout = 300000; // 5分钟超时
            int refreshCount = 0;
            final int MAX_QR_REFRESH = 3;
            
            while (System.currentTimeMillis() - startTime < timeout) {
                // 检查用户取消
                if (System.in.available() > 0) {
                    scanner.nextLine();
                    log.info("已取消登录");
                    return false;
                }
                
                // 检查登录状态
                JsonNode statusData = checkQRCodeStatus(qrcodeId);
                if (statusData == null) {
                    Thread.sleep(1000);
                    continue;
                }
                
                String status = statusData.has("status") ? statusData.get("status").asText() : "";
                
                switch (status) {
                    case "confirmed":
                        // 登录成功
                        String newToken = statusData.has("bot_token") 
                                ? statusData.get("bot_token").asText() : "";
                        if (newToken != null && !newToken.isEmpty()) {
                            this.token = newToken;
                            if (statusData.has("baseurl")) {
                                this.config.setBaseUrl(statusData.get("baseurl").asText());
                            }
                            saveState();
                            
                            String botId = statusData.has("ilink_bot_id") 
                                    ? statusData.get("ilink_bot_id").asText() : "";
                            String userId = statusData.has("ilink_user_id") 
                                    ? statusData.get("ilink_user_id").asText() : "";
                            
                            String tokenPreview = newToken.substring(0, Math.min(20, newToken.length()));
                            log.info("\n{}", "=".repeat(60));
                            log.info(String.format(WEIXIN_LOGIN_SUCCESS_TEMPLATE, botId, userId, tokenPreview));
                            log.info("{}", "=".repeat(60) + "\n");
                            
                            return true;
                        }
                        log.error("登录确认但没有获取到 token");
                        return false;
                        
                    case "scaned":
                        log.info("二维码已扫描，等待确认...");
                        break;
                        
                    case "expired":
                        refreshCount++;
                        if (refreshCount > MAX_QR_REFRESH) {
                            log.error("二维码过期次数过多，放弃登录");
                            return false;
                        }
                        log.info("二维码已过期，正在刷新... ({}/{})", refreshCount, MAX_QR_REFRESH);
                        qrData = fetchQRCode();
                        if (qrData != null) {
                            qrcodeId = qrData.get("qrcode").asText();
                            qrcodeContent = qrData.has("qrcode_img_content") 
                                    ? qrData.get("qrcode_img_content").asText() : qrcodeId;
                            displayQRCode(qrcodeContent);
                        }
                        break;
                        
                    case "wait":
                    default:
                        // 继续等待
                        break;
                }
                
                Thread.sleep(1000);
            }
            
            log.info("\n登录超时");
            return false;
            
        } catch (Exception e) {
            log.error("扫码登录异常: {}", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 【获取二维码】
     */
    private JsonNode fetchQRCode() throws IOException {
        String url = config.getBaseUrl() + "/ilink/bot/get_bot_qrcode?bot_type=3";
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .headers(makeHeaders(false))
                .build();
        
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            String body = response.body() != null ? response.body().string() : "";
            return MAPPER.readTree(body);
        }
    }
    
    /**
     * 【检查二维码状态】
     */
    private JsonNode checkQRCodeStatus(String qrcodeId) throws IOException {
        String url = config.getBaseUrl() + "/ilink/bot/get_qrcode_status?qrcode=" + qrcodeId;
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .headers(makeHeaders(false).newBuilder()
                        .add("iLink-App-ClientVersion", "1")
                        .build())
                .build();
        
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            String body = response.body() != null ? response.body().string() : "";
            return MAPPER.readTree(body);
        }
    }
    
    /**
     * 【显示二维码】
     * 生成并显示二维码，包括图片文件和ASCII控制台显示
     */
    private void displayQRCode(String content) {
        try {
            // 生成二维码图片 - 使用较小的尺寸
            int width = 150;
            int height = 150;
            
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L); // 低容错率，更小尺寸
            hints.put(EncodeHintType.MARGIN, 1); // 最小边距
            
            BitMatrix bitMatrix = new MultiFormatWriter().encode(
                    content, BarcodeFormat.QR_CODE, width, height, hints);
            
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    image.setRGB(x, y, bitMatrix.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
                }
            }
            
            // 保存图片
            File qrFile = new File("weixin_login_qr.png");
            ImageIO.write(image, "PNG", qrFile);
            System.out.println("二维码已保存到: " + qrFile.getAbsolutePath());
            
            // 显示ASCII二维码
            displayAsciiQRCode(bitMatrix);
            
        } catch (Exception e) {
            System.out.println("二维码URL: " + content);
            System.out.println("请复制URL到浏览器打开，或使用手机相机扫描");
        }
    }
    
    /**
     * 【显示ASCII二维码】
     * 使用较小的尺寸在控制台显示二维码
     */
    private void displayAsciiQRCode(BitMatrix bitMatrix) {
        System.out.println("\n【控制台二维码】\n");
        
        int width = bitMatrix.getWidth();
        int height = bitMatrix.getHeight();
        
        // 计算缩放比例，使二维码能在控制台完整显示
        // 控制台一般宽度为80-120字符，每个二维码块用2个字符
        int maxConsoleWidth = 80;
        int scale = Math.max(1, (width * 2) / maxConsoleWidth + 1);
        
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < height; y += scale) {
            for (int x = 0; x < width; x += scale) {
                // 检查这个区域是否有黑色像素
                boolean black = false;
                for (int dy = 0; dy < scale && y + dy < height && !black; dy++) {
                    for (int dx = 0; dx < scale && x + dx < width; dx++) {
                        if (bitMatrix.get(x + dx, y + dy)) {
                            black = true;
                            break;
                        }
                    }
                }
                // 使用半角字符，节省空间
                sb.append(black ? "██" : "  ");
            }
            sb.append("\n");
        }
        System.out.println(sb.toString());
        System.out.println("如果二维码显示不完整，请查看文件: weixin_login_qr.png\n");
    }
    
    // ========== 消息轮询 ==========
    
    /**
     * 【启动消息轮询】
     */
    private void startPolling() {
        pollTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                pollOnce();
            } catch (Exception e) {
                System.err.println("[Weixin] 轮询异常: " + e.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);
    }
    
    /**
     * 【轮询一次】
     */
    private void pollOnce() throws Exception {
        // 检查会话暂停
        long remaining = getSessionPauseRemaining();
        if (remaining > 0) {
            System.out.println("[Weixin] 会话暂停中，" + remaining + "秒后恢复");
            return;
        }
        
        // 构造请求体
        ObjectNode body = MAPPER.createObjectNode();
        body.put("get_updates_buf", getUpdatesBuf);
        ObjectNode baseInfo = MAPPER.createObjectNode();
        baseInfo.put("channel_version", WEIXIN_CHANNEL_VERSION);
        body.set("base_info", baseInfo);
        
        // 发送请求
        String url = config.getBaseUrl() + "/ilink/bot/getupdates";
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                .headers(makeHeaders(true))
                .build();
        
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            
            String respBody = response.body() != null ? response.body().string() : "";
            JsonNode data = MAPPER.readTree(respBody);
            
            // 检查错误
            int ret = data.has("ret") ? data.get("ret").asInt() : 0;
            int errcode = data.has("errcode") ? data.get("errcode").asInt() : 0;
            
            if (ret != 0 || errcode != 0) {
                if (errcode == ERRCODE_SESSION_EXPIRED || ret == ERRCODE_SESSION_EXPIRED) {
                    pauseSession();
                    System.out.println("[Weixin] 会话已过期，暂停1小时");
                    return;
                }
                throw new RuntimeException("API错误: ret=" + ret + ", errcode=" + errcode);
            }
            
            // 更新轮询超时
            if (data.has("longpolling_timeout_ms")) {
                int timeoutMs = data.get("longpolling_timeout_ms").asInt();
                if (timeoutMs > 0) {
                    nextPollTimeoutS = Math.max(timeoutMs / 1000, 5);
                }
            }
            
            // 更新cursor
            if (data.has("get_updates_buf")) {
                String newBuf = data.get("get_updates_buf").asText();
                if (newBuf != null && !newBuf.isEmpty()) {
                    getUpdatesBuf = newBuf;
                    saveState();
                }
            }
            
            // 处理消息
            if (data.has("msgs")) {
                JsonNode msgs = data.get("msgs");
                if (msgs.isArray()) {
                    for (JsonNode msg : msgs) {
                        processMessage(msg);
                    }
                }
            }
        }
    }
    
    /**
     * 【处理单条消息】
     */
    private void processMessage(JsonNode msg) {
        try {
            // 跳过机器人自己的消息
            int messageType = msg.has("message_type") ? msg.get("message_type").asInt() : 0;
            if (messageType == MESSAGE_TYPE_BOT) {
                return;
            }
            
            // 去重
            String msgId = msg.has("message_id") ? msg.get("message_id").asText() : "";
            if (msgId.isEmpty()) {
                msgId = msg.get("from_user_id").asText() + "_" + msg.get("create_time_ms").asText();
            }
            if (processedIds.contains(msgId)) {
                return;
            }
            processedIds.add(msgId);
            
            String fromUserId = msg.has("from_user_id") ? msg.get("from_user_id").asText() : "";
            if (fromUserId.isEmpty()) {
                return;
            }
            
            // 保存context_token（回复必需）
            if (msg.has("context_token")) {
                contextTokens.put(fromUserId, msg.get("context_token").asText());
                saveState();
            }
            
            // 解析消息内容
            StringBuilder content = new StringBuilder();
            List<String> media = new ArrayList<>();
            
            if (msg.has("item_list")) {
                JsonNode itemList = msg.get("item_list");
                if (itemList.isArray()) {
                    for (JsonNode item : itemList) {
                        int itemType = item.has("type") ? item.get("type").asInt() : 0;
                        
                        switch (itemType) {
                            case ITEM_TEXT:
                                JsonNode textItem = item.get("text_item");
                                if (textItem != null && textItem.has("text")) {
                                    content.append(textItem.get("text").asText()).append("\n");
                                }
                                break;
                                
                            case ITEM_IMAGE:
                                content.append("[图片]\n");
                                break;
                                
                            case ITEM_VOICE:
                                JsonNode voiceItem = item.get("voice_item");
                                if (voiceItem != null && voiceItem.has("text")) {
                                    content.append("[语音] ").append(voiceItem.get("text").asText()).append("\n");
                                } else {
                                    content.append("[语音]\n");
                                }
                                break;
                                
                            case ITEM_FILE:
                                JsonNode fileItem = item.get("file_item");
                                if (fileItem != null && fileItem.has("file_name")) {
                                    content.append("[文件: ").append(fileItem.get("file_name").asText()).append("]\n");
                                } else {
                                    content.append("[文件]\n");
                                }
                                break;
                                
                            case ITEM_VIDEO:
                                content.append("[视频]\n");
                                break;
                        }
                    }
                }
            }
            
            String finalContent = content.toString().trim();
            if (finalContent.isEmpty()) {
                return;
            }
            
            // 发布消息到总线
            System.out.println("[Weixin] 收到消息来自 " + fromUserId + ": " + 
                    (finalContent.length() > 50 ? finalContent.substring(0, 50) + "..." : finalContent));
            
            handleMessage(fromUserId, fromUserId, finalContent, media, 
                    Collections.singletonMap("message_id", msgId));
            
        } catch (Exception e) {
            System.err.println("[Weixin] 处理消息异常: " + e.getMessage());
        }
    }
    
    // ========== 发送消息 ==========
    
    @Override
    public void send(OutboundMessage msg) {
        if (token == null || token.isEmpty()) {
            System.err.println("[Weixin] 未登录，无法发送消息");
            return;
        }

        String chatId = msg.getChatId();
        String content = msg.getContent();

        if (chatId == null || content == null) {
            return;
        }

        System.out.println("[Weixin] 发送消息到 " + chatId + ": " + (content.length() > 50 ? content.substring(0, 50) + "..." : content));

        String ctxToken = contextTokens.get(chatId);
        if (ctxToken == null || ctxToken.isEmpty()) {
            System.err.println("[Weixin] 没有context_token，无法回复 " + chatId);
            return;
        }

        try {
            // 分割长消息
            List<String> chunks = splitMessage(content, WEIXIN_MAX_MESSAGE_LEN);
            for (int i = 0; i < chunks.size(); i++) {
                sendText(chatId, chunks.get(i), ctxToken);
            }
        } catch (Exception e) {
            System.err.println("[Weixin] 发送消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 【发送文本消息】
     */
    private void sendText(String toUserId, String text, String contextToken) throws IOException {
        String clientId = "simpleclaw-" + UUID.randomUUID().toString().substring(0, 12);

        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("from_user_id", "");
        msg.put("to_user_id", toUserId);
        msg.put("client_id", clientId);
        msg.put("message_type", MESSAGE_TYPE_BOT);
        msg.put("message_state", MESSAGE_STATE_FINISH);
        msg.put("context_token", contextToken);
        
        // 构造item_list
        ObjectNode textItem = MAPPER.createObjectNode();
        textItem.put("type", ITEM_TEXT);
        ObjectNode textContent = MAPPER.createObjectNode();
        textContent.put("text", text);
        textItem.set("text_item", textContent);
        
        msg.set("item_list", MAPPER.createArrayNode().add(textItem));
        
        ObjectNode body = MAPPER.createObjectNode();
        body.set("msg", msg);
        ObjectNode baseInfo = MAPPER.createObjectNode();
        baseInfo.put("channel_version", WEIXIN_CHANNEL_VERSION);
        body.set("base_info", baseInfo);
        
        String url = config.getBaseUrl() + "/ilink/bot/sendmessage";
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                .headers(makeHeaders(true))
                .build();
        
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            
            String respBody = response.body() != null ? response.body().string() : "";
            JsonNode data = MAPPER.readTree(respBody);
            
            int errcode = data.has("errcode") ? data.get("errcode").asInt() : 0;
            if (errcode != 0) {
                System.err.println("[Weixin] 发送失败: " + data.get("errmsg").asText());
            } else {
                System.out.println("[Weixin] HTTP 发送成功: clientId=" + clientId);
            }
        }
    }
    
    // ========== 辅助方法 ==========
    
    // ========== 状态持久化 ==========
    
    /**
     * 【获取状态目录】
     */
    private Path getStateDir() {
        String home = System.getProperty("user.home");
        Path dir = Paths.get(home, ".simpleclaw", "weixin");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // ignore
        }
        return dir;
    }
    
    /**
     * 【加载状态】
     * 从文件加载token、cursor、context_tokens等状态
     */
    private void loadState() {
        Path stateFile = getStateDir().resolve("account.json");
        if (!Files.exists(stateFile)) {
            return;
        }
        
        try {
            String content = new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8);
            JsonNode data = MAPPER.readTree(content);
            
            if (data.has("token")) {
                token = data.get("token").asText();
            }
            if (data.has("get_updates_buf")) {
                getUpdatesBuf = data.get("get_updates_buf").asText();
            }
            if (data.has("base_url")) {
                config.setBaseUrl(data.get("base_url").asText());
            }
            if (data.has("context_tokens")) {
                JsonNode tokens = data.get("context_tokens");
                if (tokens.isObject()) {
                    tokens.fields().forEachRemaining(entry -> {
                        contextTokens.put(entry.getKey(), entry.getValue().asText());
                    });
                }
            }
        } catch (IOException e) {
            System.err.println("[Weixin] 加载状态失败: " + e.getMessage());
        }
    }
    
    /**
     * 【保存状态】
     * 将token、cursor、context_tokens等状态保存到文件
     */
    private void saveState() {
        Path stateFile = getStateDir().resolve("account.json");
        
        try {
            ObjectNode data = MAPPER.createObjectNode();
            data.put("token", token);
            data.put("get_updates_buf", getUpdatesBuf);
            data.put("base_url", config.getBaseUrl());
            
            ObjectNode tokens = MAPPER.createObjectNode();
            contextTokens.forEach(tokens::put);
            data.set("context_tokens", tokens);
            
            Files.write(stateFile, data.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[Weixin] 保存状态失败: " + e.getMessage());
        }
    }
    
    /**
     * 【获取会话暂停剩余时间】
     */
    private long getSessionPauseRemaining() {
        long remaining = sessionPauseUntil - System.currentTimeMillis() / 1000;
        if (remaining <= 0) {
            sessionPauseUntil = 0;
            return 0;
        }
        return remaining;
    }
    
    /**
     * 【暂停会话】
     * 当会话过期时暂停一段时间（默认1小时）
     */
    private void pauseSession() {
        sessionPauseUntil = System.currentTimeMillis() / 1000 + 3600; // 暂停1小时
    }
    
    // ========== HTTP 辅助 ==========
    
    /**
     * 【构造请求头】
     */
    private Headers makeHeaders(boolean auth) {
        Headers.Builder builder = new Headers.Builder()
                .add("X-WECHAT-UIN", randomWechatUin())
                .add("Content-Type", "application/json")
                .add("AuthorizationType", "ilink_bot_token");
        
        if (auth && token != null && !token.isEmpty()) {
            builder.add("Authorization", "Bearer " + token);
        }
        
        if (config.getRouteTag() != null && !config.getRouteTag().toString().trim().isEmpty()) {
            builder.add("SKRouteTag", config.getRouteTag().toString().trim());
        }
        
        return builder.build();
    }
    
    /**
     * 【生成随机微信 UIN】
     */
    private String randomWechatUin() {
        byte[] bytes = new byte[4];
        new Random().nextBytes(bytes);
        long uint32 = ((bytes[0] & 0xFFL) << 24) | ((bytes[1] & 0xFFL) << 16) 
                    | ((bytes[2] & 0xFFL) << 8) | (bytes[3] & 0xFFL);
        return Base64.getEncoder().encodeToString(String.valueOf(uint32).getBytes());
    }

    /**
     * 创建分隔线
     * 
     * @param character 分隔字符
     * @param length 长度
     * @return 分隔线字符串
     */
    private String createSeparatorLine(String character, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(character);
        }
        return sb.toString();
    }
    
    /**
     * 【分割长消息】
     * 将长消息分割成多个小片段，每个片段不超过最大长度
     * 
     * @param content 原始消息内容
     * @param maxLen 每个片段的最大长度
     * @return 分割后的消息片段列表
     */
    private List<String> splitMessage(String content, int maxLen) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chunks;
        }
        if (content.length() <= maxLen) {
            chunks.add(content);
            return chunks;
        }
        
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + maxLen, content.length());
            chunks.add(content.substring(start, end));
            start = end;
        }
        
        return chunks;
    }
}
