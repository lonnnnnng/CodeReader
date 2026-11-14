package com.codereader.app.data

import java.io.IOException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

/** 草稿的取消、放弃和退出必须按磁盘事务顺序验证，不能只检查内存状态。 @author long */
class DraftPersistenceQueueTest {
    @Test
    fun `页面重建后读取仍等待旧页面的最终写入`() = runBlocking {
        val directory = File(System.getProperty("java.io.tmpdir"), "draft-session-${java.util.UUID.randomUUID()}")
        val oldOwner = DraftPersistenceSession.forDirectory(directory)
        val newOwner = DraftPersistenceSession.forDirectory(File(directory.absolutePath))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stored = AtomicReference<String?>()
        try {
            oldOwner.submit {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                stored.set("old owner's final draft")
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val reopenedRead = newOwner.submit { stored.get() }
            val latestWrite = newOwner.submit { stored.set("new owner's latest draft") }
            assertSame(oldOwner.store, newOwner.store)
            assertFalse("重建后的读取不能越过仍在进行的退出补写", reopenedRead.isCompleted)
            release.countDown()
            assertEquals("old owner's final draft", withTimeout(5_000) { reopenedRead.await() })
            withTimeout(5_000) { latestWrite.await() }
            assertEquals("new owner's latest draft", stored.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `取消等待后删除仍排在已经开始的保存之后`() = runBlocking {
        val queue = DraftPersistenceQueue()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stored = AtomicReference<String?>()
        try {
            val save = queue.submit {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                stored.set("discarded draft")
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { save.await() }
            waiter.cancelAndJoin()
            val delete = queue.submit { stored.set(null) }
            val readAfterDelete = queue.submit { stored.get() }
            release.countDown()

            withTimeout(5_000) { delete.await() }
            assertNull(withTimeout(5_000) { readAfterDelete.await() })
            assertTrue(save.isCompleted)
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test
    fun `关闭入口立即返回且已排队的最终快照仍然落盘`() = runBlocking {
        val queue = DraftPersistenceQueue()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stored = AtomicReference<String?>()
        try {
            val oldSave = queue.submit {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                stored.set("old")
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val finalSave = queue.submit { stored.set("latest") }
            queue.close()

            assertFalse("退出不能等待尚未完成的文件同步", oldSave.isCompleted)
            val rejected = runCatching { queue.submit { stored.set("unexpected") }.await() }.exceptionOrNull()
            assertTrue(rejected is RejectedExecutionException)
            release.countDown()
            withTimeout(5_000) { finalSave.await() }
            assertEquals("latest", stored.get())
        } finally {
            release.countDown()
            queue.close()
        }
    }

    @Test
    fun `保存失败交给等待方且不会阻断之后的删除和重试`() = runBlocking {
        val queue = DraftPersistenceQueue()
        try {
            val failure = runCatching { queue.submit<Unit> { throw IOException("storage failed") }.await() }
                .exceptionOrNull()
            assertEquals("storage failed", failure?.message)
            assertEquals("recovered", withTimeout(5_000) { queue.submit { "recovered" }.await() })
        } finally {
            queue.close()
        }
    }
}
