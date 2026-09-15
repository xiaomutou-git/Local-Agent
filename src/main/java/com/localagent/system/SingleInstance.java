package com.localagent.system;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 单实例锁（P0-7）：保证同一数据目录下本机助手只运行一个进程，
 * 避免多开导致 SQLite 并发写坏库、提醒重复触发、MCP 子进程重复拉起。
 *
 * 实现：在数据目录对 app.lock 做 JVM 级排他文件锁（FileLock）。
 * 锁随持有的 FileChannel/进程生命周期存在，进程崩溃或正常退出后 OS 自动释放，
 * 不会残留"假性已锁定"；锁文件本身保留在磁盘上无副作用。
 *
 * 创建时间：2026-09，核心用途：Main 启动最早期的重复启动拦截。
 */
public final class SingleInstance {
    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstance(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * 尝试获取单实例锁。
     * @param lockFile 锁文件路径（通常为数据目录下 app.lock，父目录会自动创建）
     * @return 锁实例（调用方在进程退出前持有，勿提前关闭）；已有实例运行时返回 null
     * @throws Exception 父目录创建/通道打开发生 IO 错误时抛出（与"已被锁定"语义不同）
     */
    public static SingleInstance tryAcquire(Path lockFile) throws Exception {
        Files.createDirectories(lockFile.getParent());
        FileChannel ch = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock fl;
        try {
            // tryLock 非阻塞：拿不到说明另一进程持有
            fl = ch.tryLock();
        } catch (OverlappingFileLockException e) {
            // 同一 JVM 内重复获取（理论上不会发生），按已锁定处理
            ch.close();
            return null;
        }
        if (fl == null) {
            ch.close();
            return null;
        }
        return new SingleInstance(ch, fl);
    }

    /**
     * 主动释放锁并关闭通道（正常退出一般无需调用，进程结束会自动释放）。
     * 释放/关闭异常均吞掉：退出路径上已无后续动作，抛异常没有意义。
     */
    public void release() {
        try { if (lock.isValid()) lock.release(); } catch (Exception ignored) { }
        try { channel.close(); } catch (Exception ignored) { }
    }
}
