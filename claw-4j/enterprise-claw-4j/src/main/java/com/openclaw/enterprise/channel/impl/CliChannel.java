package com.openclaw.enterprise.channel.impl;

import com.openclaw.enterprise.channel.Channel;
import com.openclaw.enterprise.channel.InboundMessage;
import com.openclaw.enterprise.channel.MediaAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * CLI 渠道 — 通过终端标准输入/输出进行交互
 *
 * <p>实现细节：</p>
 * <ul>
 *   <li>后台虚拟线程持续读取 {@code System.in}，将每行文本放入内部队列</li>
 *   <li>{@link #receive()} 非阻塞地从队列取出消息</li>
 *   <li>{@link #send(String, String)} 输出到 {@code System.out}</li>
 * </ul>
 *
 * <p>CLI 渠道始终注册 (无条件)，是开发和测试的主要交互方式。</p>
 *
 * <p>claw0 参考: s04_channels.py 中 CLI 渠道的实现</p>
 */
@Component
public class CliChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(CliChannel.class);

    /** 输入队列 — 后台 stdin 线程写入，receive() 读取 */
    private final BlockingQueue<String> inputQueue = new LinkedBlockingQueue<>();

    /** 运行标志 */
    private volatile boolean running = true;

    /** 后台 stdin 读取线程 */
    private final Thread stdinThread;

    /**
     * 构造 CLI 渠道
     *
     * <p>启动一个虚拟线程持续读取 System.in。</p>
     */
    public CliChannel() {
        stdinThread = Thread.ofVirtual()
            .name("cli-stdin")
            .unstarted(() -> {
                log.info("CLI stdin reader started");
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(System.in))) {
                    while (running && !Thread.currentThread().isInterrupted()) {
                        String line = reader.readLine();
                        if (line == null) {
                            // EOF — System.in 已关闭
                            log.info("CLI stdin EOF received");
                            break;
                        }
                        if (!line.isBlank()) {
                            inputQueue.offer(line);
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        log.error("CLI stdin reader error", e);
                    }
                }
            });
        stdinThread.start();
    }

    @Override
    public String getName() {
        return "cli";
    }

    /**
     * 非阻塞接收 CLI 输入
     *
     * <p>从内部队列取出一条输入，包装为 InboundMessage。</p>
     *
     * @return 入站消息，无输入时返回 empty
     */
    @Override
    public Optional<InboundMessage> receive() {
        String line = inputQueue.poll();
        if (line == null) {
            return Optional.empty();
        }

        return Optional.of(new InboundMessage(
            line,                           // text
            "user",                         // senderId
            "cli",                          // channel
            "cli",                          // accountId
            "user",                         // peerId
            null,                           // guildId (私聊)
            false,                          // isGroup
            List.of(),                      // media
            null,                           // raw
            Instant.now()                   // timestamp
        ));
    }

    /**
     * 发送消息到 CLI 标准输出
     *
     * @param to   目标 (CLI 忽略此参数)
     * @param text 消息文本
     * @return 始终返回 true
     */
    @Override
    public boolean send(String to, String text) {
        System.out.println(text);
        return true;
    }

    @Override
    public void close() {
        running = false;
        stdinThread.interrupt();
        log.info("CLI channel closed");
    }
}
