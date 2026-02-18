package com.example.simulator;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高性能 JT808 模拟器 (3万连接版)
 * 核心优化：使用 HashedWheelTimer 替代 EventLoop.schedule
 */
public class Jt808Simulator {

    // --- 配置区 ---
    public static final String HOST = "38.147.176.216"; // 替换你的网关IP
    public static final int PORT = 8090;
    public static final int VEHICLE_COUNT = 30000;      // 目标连接数
    public static final int INTERVAL_SEC = 3;           // 上报间隔

    // 全局计数器
    public static final AtomicInteger connectedCount = new AtomicInteger(0);

    // 【核心优化1】全局时间轮定时器
    // tickDuration=100ms, ticksPerWheel=512。用于管理海量定时任务，由单线程驱动。
    public static final HashedWheelTimer GLOBAL_TIMER = new HashedWheelTimer(
            new DefaultThreadFactory("traffic-timer"),
            100, TimeUnit.MILLISECONDS, 512);

    public static void main(String[] args) throws Exception {
        // 1. 设置线程数：IO密集型，核心数 * 2 即可
        // 【修复】使用 MultiThreadIoEventLoopGroup 替代 Deprecated 的 NioEventLoopGroup
        int threads = Runtime.getRuntime().availableProcessors() * 2;
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(threads, NioIoHandler.newFactory());

        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 初始化逻辑...
                        }
                    });

            System.out.println(">>> 启动模拟器: 目标 " + VEHICLE_COUNT + " 连接, 每 " + INTERVAL_SEC + " 秒上报");

            // 启动监控线程
            startMonitor();

            // 批量建立连接
            for (int i = 0; i < VEHICLE_COUNT; i++) {
                String deviceId = String.format("%012d", 13800000000L + i);

                // 发起连接
                connect(b, deviceId);

                // 【流控】防止瞬间流量过大
                if (i % 50 == 0) {
                    Thread.sleep(100);
                }
            }

            // 保持主线程不退出
            Thread.currentThread().join();

        } finally {
            group.shutdownGracefully();
            GLOBAL_TIMER.stop();
        }
    }
    /**
     * 发起连接的封装方法
     */
    public static void connect(Bootstrap b, String deviceId) {
        b.connect(HOST, PORT).addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                // 连接成功：绑定业务 Handler
                SocketChannel channel = (SocketChannel) future.channel();

                // 必须在这里 addLast，因为需要把 deviceId 传进去
                // 注意：SimulationHandler 是有状态的（存了 deviceId），不能 Sharable
                channel.pipeline().addLast(new SimulationHandler(b, deviceId));

            } else {
                // 连接失败：5秒后重试
                // 这里用 EventLoop 的 schedule 没问题，因为连接失败的数量很少
                future.channel().eventLoop().schedule(() -> connect(b, deviceId), 5, TimeUnit.SECONDS);
            }
        });
    }

    private static void startMonitor() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    int current = connectedCount.get();
                    System.out.printf("[监控] 在线: %d | 理论QPS: %d\n", current, current / INTERVAL_SEC);
                } catch (InterruptedException e) {}
            }
        }).start();
    }
}