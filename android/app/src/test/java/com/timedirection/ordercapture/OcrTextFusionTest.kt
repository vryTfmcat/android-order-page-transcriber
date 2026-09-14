package com.timedirection.ordercapture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextFusionTest {
    @Test
    fun structuredAccessibilityTextDoesNotImportImageAds() {
        val tree = """
            交易完成
            一家之言宠物旗舰店
            【烘焙猫粮】一家之言低温烘焙粮
            x1
            实付款 ¥29.90
            奖品编号 6900000000000000014
            领奖时间 2026-08-30 02:34:36
        """.trimIndent()
        val ocr = tree + "\n9.9味換粮放心选擇\nJeli\n磁吸锯齿开箱刀N5066\n音乐NFC标签芯片专辑封"

        val result = OcrTextFusion.fuse(tree, ocr)

        assertTrue(result.text.contains("烘焙猫粮"))
        assertFalse(result.text.contains("开箱刀"))
        assertFalse(result.text.contains("Jeli"))
        assertTrue(result.discardedLines >= 3)
    }

    @Test
    fun cleansLeadingIconsAndCommonOcrConfusions() {
        val lines = OcrTextFusion.cleanLines("く  交易咸功\n|奖品编号\n实付教 ¥24.43\n得カ无墨白扳\n联想笔记本清灰換硅脂")
        assertTrue(lines.contains("交易成功"))
        assertTrue(lines.contains("奖品编号"))
        assertTrue(lines.contains("实付款 ¥24.43"))
        assertTrue(lines.contains("得力无墨白板"))
        assertTrue(lines.contains("联想笔记本清灰换硅脂"))
    }
}
