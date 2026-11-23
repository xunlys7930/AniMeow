package com.animeow.app.data.fonts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AppFontValidationTest {
    private fun font(name: String = "animeow_sans.ttf"): ByteArray = sequenceOf(File("src/main/res/font"), File("app/src/main/res/font"))
        .map { File(it, name) }.first { it.isFile }.readBytes()

    @Test fun acceptsAllRedistributedSubsets() {
        listOf("animeow_sans.ttf", "animeow_serif.ttf", "animeow_wenkai.ttf").forEach { AppFontValidation.validate(font(it)) }
    }

    @Test fun rejectsTruncatedFilesAndUnsupportedCollections() {
        val bytes = font()
        assertThrows(IllegalArgumentException::class.java) { AppFontValidation.validate(bytes.copyOf(30)) }
        "ttcf".toByteArray().copyInto(bytes)
        assertThrows(IllegalArgumentException::class.java) { AppFontValidation.validate(bytes) }
        assertThrows(IllegalArgumentException::class.java) { AppFontValidation.validate(ByteArray(AppFontValidation.MAX_BYTES + 1)) }
    }

    @Test fun rejectsUnsignedOutOfBoundsOffsetsAndDuplicateTables() {
        val offsetCorrupt = font()
        ByteBuffer.wrap(offsetCorrupt).order(ByteOrder.BIG_ENDIAN).putInt(20, -1)
        assertThrows(IllegalArgumentException::class.java) { AppFontValidation.validate(offsetCorrupt) }
        val duplicate = font()
        duplicate.copyInto(duplicate, destinationOffset = 28, startIndex = 12, endIndex = 16)
        assertThrows(IllegalArgumentException::class.java) { AppFontValidation.validate(duplicate) }
    }

    @Test fun identifiersCannotEscapeThePrivateFontDirectory() {
        assertNull(AppFontValidation.validId("../../font.ttf"))
        assertNull(AppFontValidation.validId("A".repeat(64)))
        assertEquals("a".repeat(64), AppFontValidation.validId("a".repeat(64)))
        assertEquals("字体.ttf", AppFontValidation.displayName("\n/字\\体.ttf\r"))
    }
}
