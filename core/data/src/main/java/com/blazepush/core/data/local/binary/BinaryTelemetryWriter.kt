package com.blazepush.core.data.local.binary

import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySessionType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.RandomAccessFile

/**
 * BinaryTelemetryWriter Channel 上传输的命令。
 * 三种类型：Append（追加单条 sample）/ Flush（带 ack 的同步刷盘）/ Close（带 ack 的关闭）。
 *
 * @author CC
 * @description Writer Channel 命令 sealed class
 * @date 2026-04-30
 */
sealed class TelemetryCommand {
    data class Append(val sample: TelemetrySample) : TelemetryCommand()
    data class Flush(val ack: CompletableDeferred<Unit>) : TelemetryCommand()
    data class Close(val ack: CompletableDeferred<Unit>) : TelemetryCommand()
}

/**
 * 二进制 telemetry 文件单写入者（actor 模型）。
 * Channel 接收 Append/Flush/Close 命令，IO 协程串行写入 RandomAccessFile，30 秒定时 flush + 1000 帧安全 flush。
 *
 * @author CC
 * @description binary telemetry actor writer
 * @date 2026-04-30
 */
class BinaryTelemetryWriter {

    private var channel: Channel<TelemetryCommand>? = null
    private var scope: CoroutineScope? = null

    /**
     * 打开 writer：创建文件 + 写 22-byte header + 启动 IO 协程 + 启动 30 秒定时 flush。
     */
    fun open(filePath: String, type: TelemetrySessionType, startTimestamp: Long) {
        val ch = Channel<TelemetryCommand>(capacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND)
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        channel = ch
        scope = writerScope

        val raf = RandomAccessFile(filePath, "rw")
        raf.write(GpsBinaryFormat.encodeHeader(type, 0, startTimestamp, startTimestamp))

        // 30-second periodic flush
        writerScope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                val ack = CompletableDeferred<Unit>()
                ch.send(TelemetryCommand.Flush(ack))
            }
        }

        // Writer actor
        writerScope.launch {
            var count = 0
            var lastTs = startTimestamp
            var pendingCount = 0

            for (cmd in ch) {
                when (cmd) {
                    is TelemetryCommand.Append -> {
                        raf.write(GpsBinaryFormat.encodeSample(cmd.sample))
                        count++
                        lastTs = startTimestamp + cmd.sample.tsDeltaMs
                        pendingCount++
                        if (pendingCount >= SAFETY_FLUSH_THRESHOLD) {
                            seekFlush(raf, type, startTimestamp, count, lastTs)
                            pendingCount = 0
                        }
                    }
                    is TelemetryCommand.Flush -> {
                        seekFlush(raf, type, startTimestamp, count, lastTs)
                        pendingCount = 0
                        cmd.ack.complete(Unit)
                    }
                    is TelemetryCommand.Close -> {
                        seekFlush(raf, type, startTimestamp, count, lastTs)
                        raf.close()
                        cmd.ack.complete(Unit)
                        writerScope.cancel()
                        return@launch
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun seekFlush(
        raf: RandomAccessFile,
        type: TelemetrySessionType,
        startTs: Long,
        count: Int,
        lastTs: Long,
    ) {
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(GpsBinaryFormat.encodeHeader(type, count, startTs, lastTs))
        raf.channel.force(false)
        raf.seek(pos)
    }

    /**
     * 提交一条 sample 到 writer 队列（suspend，背压由 Channel 容量决定）。
     */
    suspend fun write(sample: TelemetrySample) {
        channel?.send(TelemetryCommand.Append(sample))
    }

    /**
     * 主动 flush：等待 IO 协程完成 header 回写 + force 刷盘后返回。
     */
    suspend fun flush() {
        val ch = channel ?: return
        val ack = CompletableDeferred<Unit>()
        ch.send(TelemetryCommand.Flush(ack))
        ack.await()
    }

    /**
     * 关闭 writer：等待最后一次 flush 完成后关闭 RandomAccessFile + cancel scope。
     */
    suspend fun close() {
        val ch = channel ?: return
        val ack = CompletableDeferred<Unit>()
        ch.send(TelemetryCommand.Close(ack))
        ack.await()
        channel = null
        scope = null
    }

    companion object {
        internal const val FLUSH_INTERVAL_MS = 30_000L
        internal const val SAFETY_FLUSH_THRESHOLD = 1000
    }
}