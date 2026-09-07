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

    @Test
    fun parsesPinduoduoOcrVariantsAndRejectsUncertainIntegerAmount() {
        val result = OrderParser.parse(
            """
            〔您的订单开始拣货·待核对〕
            测试人 137****2476 深圳市宝安区
            深圳市龙华区人民路某小区 展开
            洁柔家用纸品旗舰店品牌认证旗舰店
            品牌洁柔悬挂式抽纸
            纸4层加厚大包餐巾纸厕纸学生用
            4提4000张【家用实惠】
            订单編号:260831-111122223333444
            下单时间:2026-08-31 00:38:53
            多多支付
            〔中国银行储蓄卡(2165)支付¥790·待核对〕
            〔实付:¥79(免运费)·待核对〕
            ¥13.49
            x1
            """.trimIndent(),
        )
        assertEquals("拼多多", result.order.platform)
        assertEquals("260831-111122223333444", result.order.orderNumber)
        assertEquals("洁柔家用纸品旗舰店", result.order.merchant)
        assertEquals("打包中", result.order.status)
        assertNull(result.order.totalPaid)
        assertTrue(result.order.items.size == 1)
        assertTrue(result.order.items.first().name.contains("纸"))
        assertFalse(result.rawText.contains("137"))
        assertFalse(result.rawText.contains("人民路"))
        assertFalse(result.rawText.contains("2165"))
        assertTrue(result.warnings.any { it.contains("小数点") })
    }

    @Test
    fun parsesPaidAmountWithColonAndCurrencySymbol() {
        val result = OrderParser.parse(
            "拼多多\n订单编号:260831-111122223333444\n实付: ¥7.90(免运费)\n洁柔悬挂式抽纸 x1\n下单时间:2026-08-31 00:38:53",
        )
        assertEquals(7.9, result.order.totalPaid!!, 0.001)
        assertEquals(1, result.order.items.first().quantity)
    }

    @Test
    fun leavesShortIntegerOcrAmountForManualReviewButKeepsLargeInteger() {
        val uncertain = OrderParser.parse(
            "订单编号:260831-111122223333444\n实付:¥79\n洁柔抽纸 x1\n下单时间:2026-08-31 00:38:53",
            fromOcr = true,
        )
        assertNull(uncertain.order.totalPaid)
        assertTrue(uncertain.warnings.any { it.contains("小数点") })

        val largeInteger = OrderParser.parse(
            "订单编号:241006-111122223333444\n实付:¥4919\n实木转角书桌 x1\n下单时间:2024-10-06 16:47:44",
            fromOcr = true,
        )
        assertEquals(4919.0, largeInteger.order.totalPaid!!, 0.001)
    }

    @Test
    fun parsesDouyinOrderListAndIgnoresLiveRecommendations() {
        val result = OrderParser.parse(
            """
            [已去除敏感字段]
            更多
            全部，按钮，未选中
            待支付，1，按钮，未选中
            待发货，4，按钮，已选中
            待收货/使用，59，按钮，未选中
            评价，99+，按钮，未选中
            售后，，按钮，未选中
            碱法原麦手作碱水面包
            ¥24.90
            联系商家
            申请退款
            修改地址
            催发货
            苏越陶瓷个体店
            ¥20.00
            直播中，小米官方旗舰店手机专场直播间，热度值1453，按钮
            直播中，肖尧精品木料，热度值5，按钮
            """.trimIndent(),
            "com.ss.android.ugc.aweme",
        )
        assertEquals("order", result.kind)
        assertEquals("抖音商城", result.order.platform)
        assertEquals("待发货", result.order.status)
        assertEquals("苏越陶瓷个体店", result.order.merchant)
        assertTrue(result.order.items.any { it.name.contains("碱水面包") })
        assertFalse(result.order.items.any { it.name.contains("直播") || it.name.contains("小米官方旗舰店") })
    }

    @Test
    fun recognizesFragmentedDouyinCaptureAsOrderButDoesNotInventFields() {
        val result = OrderParser.parse(
            "打包中\n2\n4\n¥\n.7\n券后价\n.9\n9\n159\n新人价\n5380\n1820",
            "com.ss.android.ugc.aweme",
            fromOcr = true,
        )
        assertEquals("order", result.kind)
        assertEquals("抖音商城", result.order.platform)
        assertEquals("打包中", result.order.status)
        assertNull(result.order.totalPaid)
        assertTrue(result.order.items.isEmpty())
    }
}
