/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.executors

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * [Executor] that sends test to execute on ohos device via hdc.
 */
@OptIn(ExperimentalTime::class)
class OhosExecutor : Executor {
    private val hostExecutor: Executor = HostExecutor()
    private val ldPreload = "LD_PRELOAD=/data/app/el1/bundle/public/com.huawei.hmos.location/libs/arm64/libc++_shared.so"
    private val ldLibraryPath = "LD_LIBRARY_PATH="
    private val exitCodeIndicator = "hdc_shell_exit_code: "

    override fun execute(request: ExecuteRequest): ExecuteResponse {
        val localExePath = request.executableAbsolutePath
        val deviceExecFdr = "/data/local/tmp/${(1..64).map { ('a'..'z').random() }.joinToString("")}/"
        val deviceExePath = "${deviceExecFdr}${File(localExePath).name}"
        val workingDirectory = request.workingDirectory ?: File(localExePath).parentFile

        executeHdcCommand("file", "send", File(localExePath).parent, deviceExecFdr)

        val args = mutableListOf("shell", "chmod", "a+x", deviceExePath, ";", ldPreload, "${ldLibraryPath}${deviceExecFdr}", deviceExePath)
        args.addAll(request.args)

        val resp = executeHdcCommand(
            ExecuteRequest(
                executableAbsolutePath = "hdc",
                args = args,
                workingDirectory = workingDirectory,
                stdin = request.stdin,
                stdout = request.stdout,
                stderr = request.stderr,
                environment = request.environment,
                timeout = request.timeout
            )
        )
        executeHdcCommand("shell", "rm", deviceExecFdr, "-rf")
        return resp
    }

    private fun executeHdcCommand(vararg args: String) {
        val request = ExecuteRequest(
            executableAbsolutePath = "hdc",
            args = args.toMutableList(),
            workingDirectory = Paths.get("").toAbsolutePath().toFile(),
            timeout = 10.seconds
        )
        executeHdcCommand(request)
    }

    private fun executeHdcCommand(request: ExecuteRequest): ExecuteResponse {
        // hdc shell command's exit code isn't that of the command executed in device shell
        if (request.args.getOrNull(0) == "shell") {
            request.args.add(1, "\"")
            // TODO: will trailing whitespace be considered difference between outputs? @linhandev
            request.args.addAll(listOf(";", "echo", exitCodeIndicator, "$?", "\""))
        }
        val capture = ByteArrayOutputStream()
        val filtered = FilteredOutputStream(request.stdout, capture, exitCodeIndicator)
        val resp = hostExecutor.execute(request.copying { stdout = filtered })
        if (request.args.getOrNull(0) == "shell") {
            val realExitCode = capture.toString().lineSequence().lastOrNull {
                it.contains(exitCodeIndicator)
            }?.substringAfter(exitCodeIndicator)?.trim()?.toIntOrNull() ?: -1
            if (realExitCode != resp.exitCode) {
                return resp.copy(exitCode = realExitCode)
            }
        }
        return resp
    }

    private class FilteredOutputStream(
        private val out: java.io.OutputStream?,
        private val capture: ByteArrayOutputStream,
        private val filterPattern: String,
    ) :
        java.io.OutputStream() {
        private val buffer = ByteArrayOutputStream()
        override fun write(x: Int) {
            buffer.write(x); if (x == '\n'.code) flush()
        }

        override fun write(buf: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) write(buf[i].toInt())
        }

        override fun flush() {
            val line = buffer.toString(Charsets.UTF_8.name())
            capture.write(buffer.toByteArray())
            if (!line.contains(filterPattern)) {
                out?.write(buffer.toByteArray())
                out?.flush()
            }
            buffer.reset()
        }
    }
}
