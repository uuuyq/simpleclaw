package com.simpleclaw.channels;

import com.simpleclaw.bus.InboundMessage;
import com.simpleclaw.bus.MessageBus;
import com.simpleclaw.bus.OutboundMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【CLI 渠道实现】
 * 
 * 将命令行交互抽象为一个标准的 Channel，通过 MessageBus 与 Agent 解耦。
 */
@Slf4j
public class CliChannel extends BaseChannel {

    private static final String CHAT_ID = "interactive";
    private static final Object INPUT_LOCK = new Object();
    private static Scanner sharedScanner;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();

    public CliChannel(MessageBus bus) {
        super("cli", bus);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return; // 防止重复启动
        }

        log.info("命令行交互已启动。输入 /quit 退出，或直接输入消息与 Agent 对话。");

        // 【初始化共享 Scanner】
        synchronized (INPUT_LOCK) {
            if (sharedScanner == null) {
                sharedScanner = new Scanner(System.in);
            }
        }

        while (running.get()) {
            try {
                System.out.print("> ");
                String input = null;
                
                // 【同步读取】防止与其他可能的控制台操作冲突
                synchronized (INPUT_LOCK) {
                    if (sharedScanner.hasNextLine()) {
                        input = sharedScanner.nextLine().trim();
                    } else {
                        // 流结束或不可用
                        break;
                    }
                }

                if (input == null || input.isEmpty()) {
                    continue;
                }

                if (input.equalsIgnoreCase("/quit") || input.equalsIgnoreCase("/exit")) {
                    log.info("正在关闭...");
                    stop();
                    // 注意：这里不直接 System.exit，而是停止循环，让 GatewayCommand 处理后续关闭
                    break;
                }

                // 构造并发送入站消息
                InboundMessage msg = new InboundMessage(name, "user", CHAT_ID, input);
                bus.publishInbound(msg);

                // 阻塞等待回复
                String response = responseQueue.take();
                System.out.println(response);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 在 IDEA 调试或某些环境下，Scanner 可能会抛出 NoSuchElementException
                if (e instanceof java.util.NoSuchElementException) {
                    log.info("检测到输入流结束。");
                    break;
                }
                log.error("处理输入时出错: {}", e.getMessage());
            }
        }
        
        running.set(false);
    }

    @Override
    public void stop() {
        running.set(false);
        // 不要在这里关闭 scanner，因为它是共享的且关联 System.in
    }

    @Override
    public void send(OutboundMessage msg) {
        if (msg != null && msg.getContent() != null) {
            try {
                responseQueue.put(msg.getContent());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
