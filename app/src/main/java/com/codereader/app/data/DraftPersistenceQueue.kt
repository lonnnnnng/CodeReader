package com.codereader.app.data

import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * 草稿读取、写入和删除按提交顺序在单个后台线程执行，放弃后不会再被旧写入恢复。
 * 等待方取消只停止等待；已经提交的磁盘事务继续完成，独立队列关闭时排空已提交操作。
 *
 * @author long
 */
internal class DraftPersistenceQueue : Closeable {
    private val executor = Executors.newSingleThreadExecutor { operation ->
        Thread(operation, "reader-drafts").apply { isDaemon = true }
    }

    fun <T> submit(operation: () -> T): Deferred<T> {
        val completion = CompletableDeferred<T>()
        try {
            executor.execute {
                runCatching(operation).fold(
                    onSuccess = { completion.complete(it) },
                    onFailure = { completion.completeExceptionally(it) },
                )
            }
        } catch (error: RejectedExecutionException) {
            completion.completeExceptionally(error)
        }
        return completion
    }

    override fun close() {
        // shutdown 不等待文件同步，也不丢弃已排队事务，生命周期回调无需承担磁盘耗时。 @author long
        executor.shutdown()
    }
}

/** 页面重建共用目录事务，新页面的读取排在旧页面退出补写之后，AtomicFile 不会被两个存储实例并发操作。 @author long */
internal class DraftPersistenceSession private constructor(directory: File) {
    val store = DraftStore(directory)
    private val queue = DraftPersistenceQueue()

    fun <T> submit(operation: () -> T): Deferred<T> = queue.submit(operation)

    companion object {
        private val sessions = mutableMapOf<String, DraftPersistenceSession>()

        @Synchronized
        fun forDirectory(directory: File): DraftPersistenceSession =
            sessions.getOrPut(directory.absolutePath) { DraftPersistenceSession(directory) }
    }
}
