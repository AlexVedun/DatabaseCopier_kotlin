package com.example.databasecopier.dump

import java.io.RandomAccessFile

/**
 * Буферизированное побайтовое чтение поверх [RandomAccessFile] вперёд по файлу, с поддержкой
 * [seek] (для курсора byte-offset) и однобайтового "push back" (нужен для разбора экранирования
 * вида `''` внутри строковых литералов, где решение "строка закончилась или это escape" зависит
 * от следующего байта).
 */
class RafByteReader(private val raf: RandomAccessFile, bufferSize: Int = 64 * 1024) {
    private val buffer = ByteArray(bufferSize)
    private var bufLen = 0
    private var bufPos = 0
    private var filePos = 0L
    private var pushedBack = -1

    fun seek(offset: Long) {
        raf.seek(offset)
        bufLen = 0
        bufPos = 0
        filePos = offset
        pushedBack = -1
    }

    fun position(): Long = filePos + bufPos - (if (pushedBack != -1) 1 else 0)

    fun readByte(): Int {
        if (pushedBack != -1) {
            val b = pushedBack
            pushedBack = -1
            return b
        }
        if (bufPos >= bufLen) {
            filePos += bufLen
            bufPos = 0
            bufLen = raf.read(buffer)
            if (bufLen <= 0) return -1
        }
        return buffer[bufPos++].toInt() and 0xFF
    }

    fun pushBack(b: Int) {
        check(pushedBack == -1) { "only one byte of pushback is supported" }
        pushedBack = b
    }

    /** Читает строку (до '\n' или EOF), не включая символ перевода строки. Возвращает null на EOF. */
    fun readLine(): String? {
        // Байты накапливаются как есть и декодируются в UTF-8 только в конце — побайтовый поиск
        // '\n'/'\r' безопасен для UTF-8, т.к. байты многобайтовых последовательностей всегда >= 0x80
        // и никогда не совпадают с ASCII-байтами перевода строки.
        val bytes = java.io.ByteArrayOutputStream()
        var any = false
        while (true) {
            val b = readByte()
            if (b == -1) return if (any) bytes.toString(Charsets.UTF_8.name()) else null
            any = true
            if (b == '\n'.code) break
            if (b == '\r'.code) continue
            bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
