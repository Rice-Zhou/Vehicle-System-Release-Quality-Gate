package com.ricezhou.vsrqg.agent

import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
class ScreenshotCollector(private val device:SmokeDevice):FileCollector("SCREENSHOT","image/png","screenshot.png",8388608) {
    override fun bytes(window:CollectionWindow):ByteArray {
        ensure(device.foreground(),"SCREENSHOT_FOREGROUND_REQUIRED")
        val bytes=device.screenshot()
        ensure(bytes.size in 8..8388608 && bytes.take(8)==listOf<Byte>(-119,80,78,71,13,10,26,10),"PNG_INVALID")
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use {stream ->
                val readers=ImageIO.getImageReadersByFormatName("PNG");ensure(readers.hasNext(),"PNG_DECODER_UNAVAILABLE")
                val reader=readers.next()
                try {reader.input=stream;val width=reader.getWidth(0);val height=reader.getHeight(0)
                    ensure(width>0 && height>0 && width.toLong()*height<=16777216,"PNG_DIMENSIONS_LIMIT")
                    ensure(reader.read(0)!=null,"PNG_INVALID")
                } finally {reader.dispose()}
            }
        } catch(_:java.io.IOException) {throw AgentFailure("PNG_INVALID")}
        return bytes
    }
}
