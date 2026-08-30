package com.timedirection.ordercapture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderParserTest {
    @Test
    fun parsesOrderAndRedactsSensitiveFields() {
        val result = OrderParser.parse(
            """
            拼多多
            待收货
            店铺：测试数码店
            100W 氮化镓充电器 白色 ¥99.80 x2
            实付款：99.80
            订单号：260831-123456789011169
            下单时间：2026-08-31 12:30
            收货地址：深圳市某处
            联系电话：13800138000
            """.trimIndent(),
            "com.xunmeng.pinduoduo",
        )
        assertEquals("order", result.kind)
        assertEquals("拼多多", result.order.platform)
        assertEquals("260831-123456789011169", result.order.orderNumber)
        assertEquals(99.8, result.order.totalPaid!!, 0.001)
        assertEquals("待收货", result.order.status)
        assertTrue(result.order.items.isNotEmpty())
        assertFalse(result.rawText.contains("深圳市某处"))
        assertFalse(result.rawText.contains("13800138000"))
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun mergesAdditionalPageWithoutDuplicateLines() {
        val first = OrderParser.parse("订单号：ABCDEF1234\n实付款：20.00\n商品甲")
        val second = OrderParser.parse("订单号：ABCDEF1234\n商品甲\n下单时间：2026-08-31 12:30")
        val merged = OrderParser.merge(first, second)
        assertEquals(first.captureId, merged.captureId)
        assertEquals(1, merged.rawText.lineSequence().count { it == "商品甲" })
        assertEquals("2026-08-31 12:30", merged.order.orderedAt)
    }

    @Test
    fun genericPageRemainsGeneric() {
        val result = OrderParser.parse("这是一段普通页面正文\n没有订单字段")
        assertEquals("generic", result.kind)
        assertNull(result.order.totalPaid)
    }

    @Test
    fun doesNotInferQuantityFromPrice() {
        val result = OrderParser.parse("订单号：ABCDEF1234\n实付款：128.00\n测试硬盘盒")
        assertTrue(result.order.items.isNotEmpty())
        assertNull(result.order.items.first().quantity)
    }
}
