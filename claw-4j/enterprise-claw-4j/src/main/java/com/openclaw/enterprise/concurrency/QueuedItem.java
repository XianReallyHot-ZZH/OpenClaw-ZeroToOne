package com.openclaw.enterprise.concurrency;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * 队列项 — 封装待执行的任务及其 Future 和代数
 *
 * <p>代数 (generation) 用于在 reset 后识别并取消过时的任务。
 * 当 {@code LaneQueue.reset()} 递增代数时，所有旧代数的任务
 * 在被 pump 取出时会被跳过并取消。</p>
 *
 * @param task       要执行的任务
 * @param future     任务完成时设置的 Future
 * @param generation 入队时的代数
 */
record QueuedItem(Callable<Object> task, CompletableFuture<Object> future, int generation) {}
