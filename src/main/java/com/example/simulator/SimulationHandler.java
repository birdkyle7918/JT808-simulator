package com.example.simulator;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 负责处理单个连接的生命周期和上报逻辑
 */
public class SimulationHandler extends ChannelInboundHandlerAdapter implements TimerTask {

    private final Bootstrap bootstrap;
    private final String deviceId;
    private final byte[] bcdId;

    // 保存定时任务的引用，以便断线时取消，防止内存泄露
    private volatile Timeout timeout;

    public SimulationHandler(Bootstrap bootstrap, String deviceId) {
        this.bootstrap = bootstrap;
        this.deviceId = deviceId;
        this.bcdId = stringToBcd(deviceId);
    }


    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Jt808Simulator.connectedCount.decrementAndGet();

        // 1. 取消定时上报任务（非常重要！否则Channel没了任务还在跑，会报错）
        if (timeout != null && !timeout.isCancelled()) {
            timeout.cancel();
        }

        System.out.println("连接断开(" + deviceId + ")，准备重连...");

        // 2. 触发重连机制
        // 注意：这里我们调回主类的 connect 方法
        // 延迟 5 秒重连，避免死循环冲击
        ctx.channel().eventLoop().schedule(() -> {
            Jt808Simulator.connect(bootstrap, deviceId);
        }, 5, TimeUnit.SECONDS);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 发生异常只关闭，关闭后会自动触发 channelInactive 进行重连
        ctx.close();
    }

    /**
     * 这是 HashedWheelTimer 触发的方法
     */
    @Override
    public void run(Timeout timeout) {
        // 获取当前的 Channel
        // 注意：Timer 线程不是 Netty 的 IO 线程，所以要拿到 Channel 上下文
        // 这里我们没有保存 ctx，因为 channel() 方法可以从外部获取，或者我们可以稍微改一下结构
        // 但最简单的办法是：我们在 channelActive 里其实拿不到 ctx 传给 TimerTask
        // 所以更好的办法是：让 Handler 自己保存 ctx，或者由外部类触发

        // 修正：run 方法里拿不到 ctx。我们需要一点小技巧。
        // 上面的 implements TimerTask 导致 run 方法没有 ctx 参数。
        // 我们改为使用 Lambda 表达式在 channelActive 里注册任务，这样就能捕获 ctx 了。
    }

    // --- 修正后的 Timer 逻辑 ---
    // 为了代码清晰，我重写 channelActive 和 runTask

    // 真正的上报任务逻辑
    private void runTask(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive() || !ctx.channel().isWritable()) {
            return; // 连接断了或忙，本轮跳过
        }

        try {
            // 1. 发送数据
            ByteBuf packet = generatePacket(ctx.alloc(), bcdId);
            ctx.writeAndFlush(packet);

            // 2. 递归注册下一次任务 (实现 Schedule 效果)
            timeout = Jt808Simulator.GLOBAL_TIMER.newTimeout(t -> runTask(ctx),
                    Jt808Simulator.INTERVAL_SEC, TimeUnit.SECONDS);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 覆盖上面的 channelActive
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Jt808Simulator.connectedCount.incrementAndGet();

        // 第一次随机延迟
        long delay = ThreadLocalRandom.current().nextLong(Jt808Simulator.INTERVAL_SEC * 1000);

        // 注册第一次任务
        timeout = Jt808Simulator.GLOBAL_TIMER.newTimeout(t -> runTask(ctx), delay, TimeUnit.MILLISECONDS);
    }

    private ByteBuf generatePacket(io.netty.buffer.ByteBufAllocator allocator, byte[] bcdId) {
        // 【优化】32字节，避免扩容
        ByteBuf buf = allocator.buffer(32);
        buf.writeByte(0x7E);
        buf.writeShort(0x0200); // 0x0200 位置上报
        buf.writeBytes(bcdId);
        // 模拟坐标
        buf.writeInt((int) ((28.2 + ThreadLocalRandom.current().nextDouble(0.1)) * 1_000_000));
        buf.writeInt((int) ((112.9 + ThreadLocalRandom.current().nextDouble(0.1)) * 1_000_000));
        buf.writeByte(0x7E);
        return buf;
    }

    private static byte[] stringToBcd(String s) {
        int len = s.length();
        byte[] bcd = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bcd[i / 2] = (byte) ((Integer.parseInt(s.substring(i, i + 1)) << 4)
                    | Integer.parseInt(s.substring(i + 1, i + 2)));
        }
        return bcd;
    }
}