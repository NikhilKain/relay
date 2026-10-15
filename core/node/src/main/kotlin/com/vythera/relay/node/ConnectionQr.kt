package com.vythera.relay.node

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

object ConnectionQr {
    fun matrix(text: String): List<List<Boolean>> {
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
        return List(bits.height) { y -> List(bits.width) { x -> bits[x, y] } }
    }
    fun decode(width: Int, height: Int, pixels: IntArray): String {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 16_000_000)
        val source = RGBLuminanceSource(width, height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)).text
    }
}
