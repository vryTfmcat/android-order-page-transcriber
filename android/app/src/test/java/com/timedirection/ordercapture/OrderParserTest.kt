package com.timedirection.ordercapture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderParserTest {
    @Test
    fun parsesDouyinPayAfterDeliveryAmountsWithYenAndOcrY() {
        val yen = OrderParser.parse(
            "打包中\n康师傅饮品旗舰店\n【500ml*15】康师傅茉莉花茶 ¥64.90\n确认收货后付款 ¥20.23\n订单编号 6900000000000000001复制",
            "com.ss.android.ugc.aweme",
            fromOcr = true,
        )
        val ocrY = OrderParser.parse(
            "打包中\n松能官方旗舰店\nT660-1W 松能显示器 109.00\n确认收货后付款 Y68.00\n订单编号 6900000000000000002复制",
            "com.ss.android.ugc.aweme",
            fromOcr = true,
        )

        assertEquals(20.23, yen.order.totalPaid!!, 0.001)
        assertEquals(68.0, ocrY.order.totalPaid!!, 0.001)
    }

    @Test
    fun cancelledOrderKeepsPayableAmountOutOfTotalPaid() {
        val result = OrderParser.parse(
            "交易关闭\nWD-40官方旗舰店\n多用途除锈套装 ¥54.00\n应付款 ¥23.90\n订单编号 6900000000000000003复制",
            "com.ss.android.ugc.aweme",
            fromOcr = true,
        )

        assertNull(result.order.totalPaid)
        assertTrue(result.warnings.any { it.contains("仅有应付款 23.9") && it.contains("未作为实付") })
        assertFalse(result.warnings.any { it == "实付金额未识别，请人工核对" })
    }

    @Test
    fun doesNotUseDouyinBalanceDiscountAsProductAndRemovesQuotedMaskedPhone() {
        val result = OrderParser.parse(
            """
            打包中
            绵绣江南2期3栋6单元213
            测试用户 138**"0000 号码保护中
            来自抖音商城版
            俏客萌官方旗舰店
            俏客萌 官方旗舰店 俏客萌猫零食狗零食鸡肉 ¥34.90
            pet 鸡肉碎500g(经典装) X1
            金币+余额抵扣 -¥6.28
            确认收货后付款 ¥14.66
            订单编号 6900000000000000004复制
            """.trimIndent(),
            "com.ss.android.ugc.aweme",
            fromOcr = true,
        )

        assertEquals(14.66, result.order.totalPaid!!, 0.001)
        assertEquals("俏客萌官方旗舰店", result.order.merchant)
        assertTrue(result.order.items.first().name.contains("猫零食"))
        assertFalse(result.order.items.first().name.contains("金币"))
        assertFalse(result.rawText.contains("138**\"0000"))
    }

    @Test
    fun parsesDouyinPaidAmountWhenOcrReadsKuanAsJiao() {
        val cleaned = OcrTextFusion.fuse(
            "",
            """
            已签收
            王小卤官方旗舰店
            王小卤红油豉汁300g 虎皮凤...69.90
            【超值组合】广式豉汁300g*2袋 X1+红油麻辣300g*1袋
            商品总价 ¥69.90
            平台优惠 -¥35.00
            金币+余额抵扣 -¥10.47
            实付教 ¥24.43
            订单编号 6900000000000000005复制
            下单时间 2026-09-11 02:03:36
            """.trimIndent(),
        ).text

        val result = OrderParser.parse(cleaned, "com.ss.android.ugc.aweme", fromOcr = true)

        assertEquals(24.43, result.order.totalPaid!!, 0.001)
        assertEquals("王小卤官方旗舰店", result.order.merchant)
        assertFalse(result.order.items.first().name.endsWith("69.90"))
        assertEquals("6900000000000000005", result.order.orderNumber)
    }

    @Test
    fun parsesOrderKeepsAddressAndRedactsPhoneByDefault() {
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
        assertTrue(result.rawText.contains("深圳市某处"))
        assertEquals("深圳市某处", result.order.shippingAddress)
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
            测试人 138****0000 示例市测试区
            示例市测试区示例路某小区 展开
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
        assertFalse(result.rawText.contains("138"))
        assertTrue(result.rawText.contains("示例路"))
        assertTrue(result.order.shippingAddress.contains("示例路"))
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

    @Test
    fun prioritizesPinduoduoStructuredProductAndParsesCommaSeparatedPaidAmount() {
        val result = OrderParser.parse(
            """
            拼多多
            返回
            拼团中
            Moriste魅丽家居
            商品名称：ins餐具卡通陶瓷可爱碟子家用火锅调料调味小吃造型酱油零食碟子,单价: 4.55 元,规格描述：小吃碟 - 水壶,数量：1个
            使用3元平台无门槛券,实付:,1.55元,(免运费)
            订单编号： 260101-000000000000001
            下单时间： 2026-09-08 09:01:09
            【宏碁】OMR326无线鼠标充电静音轻量化USB台式笔记本电脑通用电池款
            ¥14
            """.trimIndent(),
            "com.xunmeng.pinduoduo",
        )
        assertEquals("拼团中", result.order.status)
        assertEquals("Moriste魅丽家居", result.order.merchant)
        assertEquals(1.55, result.order.totalPaid!!, 0.001)
        assertEquals(1, result.order.items.size)
        assertTrue(result.order.items.first().name.startsWith("ins餐具"))
        assertEquals("小吃碟 - 水壶", result.order.items.first().specification)
        assertEquals(1, result.order.items.first().quantity)
        assertFalse(result.title.contains("宏碁"))
    }

    @Test
    fun parsesFragmentedDouyinOrderDetailFields() {
        val result = OrderParser.parse(
            """
            叮当布艺>
            |商品总价
            订单运费
            实付款
            订单编号
            平台优惠満13减2
            [已去除敏感字段]
            交易快照
            支付方式
            下单时间
            付款时间
            发货时间
            成交时间
            叮当布艺特价窗纱样品纱帘...¥10.90
            10.9
            交易完成
            x1
            ¥10.90
            ¥0.00
            6900000000000000009 复制
            -1.66
            [已去除敏感字段]
            Y9.24
            2026-09-06 17:55:28
            2026-09-06 17:55:29
            抖音月付>
            2026-09-06 18:15:14
            2026-09-08 14:15:32
            """.trimIndent(),
            "com.ss.android.ugc.aweme",
        )

        assertEquals("抖音商城", result.order.platform)
        assertEquals("叮当布艺", result.order.merchant)
        assertEquals("6900000000000000009", result.order.orderNumber)
        assertEquals(9.24, result.order.totalPaid!!, 0.001)
        assertEquals("交易完成", result.order.status)
        assertEquals("2026-09-06 17:55:28", result.order.orderedAt)
        assertEquals(1, result.order.items.size)
        assertTrue(result.order.items.first().name.startsWith("叮当布艺特价窗纱"))
        assertEquals(1, result.order.items.first().quantity)
        assertEquals(9.24, result.order.items.first().linePrice!!, 0.001)
    }

    @Test
    fun redactsStandaloneTrackingNumberAndNormalizesPinduoduoShippingStatus() {
        val result = OrderParser.parse(
            """
            拼多多
            已按时发货
            商品名称：测试支架,单价: 25.99 元,规格描述：黑色,数量：1个
            实付：13.99元
            订单编号：260101-000000000000002
            快递单号：
            JT5523990020319
            下单时间：2026-09-07 18:15:37
            """.trimIndent(),
            "com.xunmeng.pinduoduo",
        )

        assertEquals("已发货", result.order.status)
        assertFalse(result.rawText.contains("JT5523990020319"))
        assertTrue(result.warnings.any { it.contains("物流编号") })
    }

    @Test
    fun parsesDouyinPostpayOrderAndUsesStoreAfterSourceLabel() {
        val result = OrderParser.parse(
            """
            く
            ·预计明天送达
            【无锡市】快件已到达无锡转运中心
            [已去除敏感字段]
            来自抖音商坡版
            碱法原表手作碱水面包
            胡萝卜碱水棒6个装
            商品总价
            订单运费
            金币+余额抵扣
            平台优惠満24减12
            确认收货后付款
            订单编号
            运输中
            下单时间
            【胡萝卜椰耶米包】碱法原麦碱...¥24.90
            X1
            ¥24.90
            ¥0.00
            -¥12.00
            -¥3.87
            Y9.03
            6900000000000000006复制
            2026-09-07 22:49:45
            2026-09-07 22:49:47
            """.trimIndent(),
            "com.ss.android.ugc.aweme",
        )

        assertEquals("碱法原表手作碱水面包", result.order.merchant)
        assertEquals("6900000000000000006", result.order.orderNumber)
        assertEquals(9.03, result.order.totalPaid!!, 0.001)
        assertEquals("运输中", result.order.status)
        assertEquals("2026-09-07 22:49:45", result.order.orderedAt)
        assertTrue(result.order.items.first().name.startsWith("【胡萝卜椰耶米包】"))
    }

    @Test
    fun parsesDouyinVoucherAndRedactsRedeemableCode() {
        val result = OrderParser.parse(
            """
            く待便用
            超值券
            请在2027.09.02(含)前到店消费
            适用门店
            【镇店招牌】汉中热米皮>
            /素镇凉米皮2选1单人餐
            券号1242 9941 3621 250 ·复制
            休息中10:00-22:00
            何记老西安美食(傅誉府..
            订单详情 订单号:1116999313168665988,复制
            实付¥11.79 >
            x1
            """.trimIndent(),
            "com.ss.android.ugc.aweme",
        )

        assertEquals("何记老西安美食", result.order.merchant)
        assertEquals("1116999313168665988", result.order.orderNumber)
        assertEquals(11.79, result.order.totalPaid!!, 0.001)
        assertEquals("待使用", result.order.status)
        assertEquals(1, result.order.items.size)
        assertEquals("【镇店招牌】汉中热米皮/素镇凉米皮2选1单人餐", result.order.items.first().name)
        assertEquals(1, result.order.items.first().quantity)
        assertFalse(result.rawText.contains("1242 9941 3621 250"))
    }

    @Test
    fun cleansDouyinFlagshipBadgeSeparatesSpecificationAndRedactsPlusMaskedPhone() {
        val result = OrderParser.parse(
            """
            ·最晚明天发货
            测试用户 138+***0000 号码保护中
            deli ·得力官方旗舰店优选号推荐·来自抖音商域版>
            得力官方旗舰店 族舰)
            商品总价
            订单运费
            确认收货后付款
            打包中
            订单编号
            下单时间
            得カ无墨白扳学生书写草稿...
            【中号“ipad 大小】M附板擦+磁
            性笔(151*208mm)
            X1
            ¥49.90
            ¥0.00
            -¥23.00
            6900000000000000007 复制
            ¥26.90
            2026-09-08 20:50:11
            """.trimIndent(),
            "com.ss.android.ugc.aweme",
        )

        assertEquals("得力官方旗舰店", result.order.merchant)
        assertEquals("6900000000000000007", result.order.orderNumber)
        assertEquals(26.9, result.order.totalPaid!!, 0.001)
        assertEquals("打包中", result.order.status)
        assertEquals("得カ无墨白扳学生书写草稿...", result.order.items.first().name)
        assertEquals("【中号“ipad 大小】M附板擦+磁性笔(151*208mm)", result.order.items.first().specification)
        assertFalse(result.rawText.contains("138+***0000"))
    }

    @Test
    fun parsesIntegerPaidAmountAndNonBracketedDouyinServiceVoucher() {
        val result = OrderParser.parse(
            """
            く待到店便用
            请在2026.09.19(含)前到店消费
            超值券
            11.5元待使用>
            Lenovo 联想
            团购通用
            (联想笔记本清灰換硅脂 学生特惠【联想】全系列 >
            请提前1天预约
            修东方· 联想 lenovo 电脑维修服务中心、联想电脑售... >
            适用门店(42家)
            [已去除敏感字段]
            Lenovo 联想 休息中9:00-22:00
            联想lenovo电脑售后维..
            订单号:1115724183980185988·复制
            x1
            实付¥75>
            """.trimIndent(),
            "com.ss.android.ugc.aweme",
            fromOcr = true,
        )

        assertEquals("联想lenovo电脑售后维", result.order.merchant)
        assertEquals("1115724183980185988", result.order.orderNumber)
        assertEquals(75.0, result.order.totalPaid!!, 0.001)
        assertEquals("待到店使用", result.order.status)
        assertEquals("(联想笔记本清灰換硅脂 学生特惠【联想】全系列", result.order.items.first().name)
        assertTrue(result.order.items.first().specification.isBlank())
        assertEquals(1, result.order.items.first().quantity)
    }

    @Test
    fun parsesDouyinStoreVoucherStatusMerchantAndShortContinuation() {
        val result = OrderParser.parse(
            """
            く待到店便用
            超值券
            团购逼用
            【晚餐加菜】塞厨私法式>
            烤鸡
            券号1234 5678 9012 3456
            休息中7:00-23:00
            塞厨私便使利超市(龙华店)
            o赛厨私
            订单详情订单号:1112119314449305988·复制
            实付¥6.90 >
            x1
            """.trimIndent(),
            "com.ss.android.ugc.aweme",
        )

        assertEquals("待到店使用", result.order.status)
        assertEquals("赛厨私便利超市(龙华店)", result.order.merchant)
        assertEquals("【晚餐加菜】塞厨私法式烤鸡", result.order.items.first().name)
        assertEquals(6.9, result.order.totalPaid!!, 0.001)
    }

    @Test
    fun parsesBareDouyinPaidValueAfterOrderNumber() {
        val result = OrderParser.parse(
            """
            苏越陶瓷个体店
            商品总价
            订单运费
            确认收货后付款
            运输中
            物**猫瓷瑕疵品水碗两个08...¥20.00
            x1
            ¥20.00
            ¥0.00
            -¥3.00
            6900000000000000008复制
            17.00
            2026-09-07 16:19:53
            """.trimIndent(),
            "com.ss.android.ugc.aweme",
        )

        assertEquals(17.0, result.order.totalPaid!!, 0.001)
        assertEquals(17.0, result.order.items.first().linePrice!!, 0.001)
    }

    @Test
    fun parsesIdlefishZeroWidthFieldsAndRedactsWechatTransactionId() {
        val zwsp = "\u200b"
        fun spaced(value: String) = value.toCharArray().joinToString(zwsp)
        val wechatTransaction = "99000000000000000000000000001"
        val result = OrderParser.parse(
            """
            交易成功
            apple礼品卡美区苹果礼品卡 美服 ios2-300美充值
            ¥136.86
            美金:20
            该订单来自微信小程序
            成交价
            ¥136.86
            商品总价
            ¥136.86
            订单编号
            ${spaced("3316829161214011694")}
            复制
            微信交易号
            ${spaced(wechatTransaction)}
            复制
            卖家昵称
            ${spaced("Orm礼卡")}
            下单时间
            ${spaced("2026-09-07 12:38:19")}
            """.trimIndent(),
            "com.taobao.idlefish",
        )

        assertEquals("闲鱼", result.order.platform)
        assertEquals("3316829161214011694", result.order.orderNumber)
        assertEquals(136.86, result.order.totalPaid!!, 0.001)
        assertEquals("Orm礼卡", result.order.merchant)
        assertEquals("2026-09-07 12:38:19", result.order.orderedAt)
        assertTrue(result.order.items.first().name.startsWith("apple礼品卡"))
        assertEquals("美金:20", result.order.items.first().specification)
        assertFalse(result.rawText.contains(wechatTransaction))
        assertFalse(result.title.contains(zwsp))
    }

    @Test
    fun parsesTaobaoFragmentedOrderAndRedactsLongPaymentIdentifier() {
        val paymentIdentifier = "202605022300112091410243968"
        val result = OrderParser.parse(
            """
            HINHUA BOOKSTORE
            支付方式
            实付款 减¥3优惠解析>
            支付宝交易号
            订单信息共8项へ
            创建时间
            付款时间
            交易成功
            论技术物的存在模式
            订单保障共3项
            实付价¥27.42 价格明细〉
            x1
            32994621133220116941复制
            |支付宝支付
            ******************米******
            $paymentIdentifier
            2026-05-02 09:40:51
            2026-05-02 09:40:52
            2026-05-12 13:30:21
            埃晉教娱
            凭据:5月2日下单交易快照>
            """.trimIndent(),
            "com.taobao.taobao",
        )

        assertEquals("淘宝", result.order.platform)
        assertEquals("32994621133220116941", result.order.orderNumber)
        assertEquals(27.42, result.order.totalPaid!!, 0.001)
        assertEquals("埃晉教娱", result.order.merchant)
        assertEquals("2026-05-02 09:40:51", result.order.orderedAt)
        assertEquals("论技术物的存在模式", result.order.items.first().name)
        assertEquals(1, result.order.items.first().quantity)
        assertFalse(result.rawText.contains(paymentIdentifier))
    }

    @Test
    fun parsesDouyinLiteCompletedOrderAndPairsPaidBeforeOrderNumber() {
        val result = OrderParser.parse(
            """
            く
            叮当布艺直播>
            商品总价
            订单运费
            实付款
            订单编号
            物流信息
            交易完成
            可当布艺特价窗纱一样晶纱帘... ¥10.90
            X1
            ¥10.90
            ¥0.00
            一¥1.66
            0755-27035359、0755-36553673,投诉电话
            9.24
            6900000000000000009复制
            下单时间
            2026-09-06 17:55:28
            """.trimIndent(),
            "com.ss.android.ugc.livelite",
            fromOcr = true,
        )

        assertEquals("抖省省", result.order.platform)
        assertEquals("叮当布艺", result.order.merchant)
        assertEquals("6900000000000000009", result.order.orderNumber)
        assertEquals(9.24, result.order.totalPaid!!, 0.001)
        assertEquals("2026-09-06 17:55:28", result.order.orderedAt)
        assertTrue(result.order.items.first().name.contains("窗纱"))
        assertFalse(result.rawText.contains("0755-"))
    }

    @Test
    fun parsesDouyinLitePetFoodAndKeepsSpecificationOutOfTitle() {
        val result = OrderParser.parse(
            """
            喵梵思宠物食品旗舰店 音旗舰) 直播 >
            商品总价
            订单运费
            金币抵扣
            实付款
            订单编号
            交易完成
            【満仓5.9折 宠物磷虾油】喵... ¥29.44
            宠物南极磷虾油15ml(2026年1月生产)
            X1
            ¥29.44
            ¥0.00
            -¥4.19
            -¥7.57
            ¥17.68
            6900000000000000010复制
            0755-28044417
            下单时间
            2026-09-07 13:35:27
            """.trimIndent(),
            "com.ss.android.ugc.livelite",
        )

        assertEquals("喵梵思宠物食品旗舰店", result.order.merchant)
        assertEquals(17.68, result.order.totalPaid!!, 0.001)
        assertEquals("【満仓5.9折 宠物磷虾油】喵...", result.order.items.first().name)
        assertTrue(result.order.items.first().specification.contains("15ml"))
        assertFalse(result.rawText.contains("0755-28044417"))
    }

    @Test
    fun excludesPickupNoticeFromDouyinProduct() {
        val result = OrderParser.parse(
            """
            喵梵思宠物食品旗舰店音旗舰 @直播>
            商品总价
            订单运费
            实付款
            订单编号
            交易完成
            【5折上新0淀粉烘焙粮】喵..
            【日常价119元】强健猎手烘焙粮3斤*1袋(限2单)
            x1
            ¥49.90
            ¥0.00
            -¥12.97
            -¥11.08
            ¥25.85
            6900000000000000011 复制
            【代收点】您的快件已投递,收件人凭取件码在
            【示例市测试区示例小区2期6栋东门店(已签收签收人凭取货码签收)】领取
            单元213
            下单时间
            2026-09-06 00:01:15
            """.trimIndent(),
            "com.ss.android.ugc.livelite",
        )

        assertEquals(25.85, result.order.totalPaid!!, 0.001)
        assertTrue(result.order.items.first().name.contains("烘焙粮"))
        assertFalse(result.order.items.first().name.contains("代收点"))
        assertTrue(result.rawText.contains("示例小区"))
        assertTrue(result.rawText.contains("单元213"))
        assertTrue(result.order.shippingAddress.contains("示例小区"))
    }

    @Test
    fun addressCanStillBeDisabledWithoutRelaxingPhoneFiltering() {
        val result = OrderParser.parse(
            "订单号：ABCDEF1234\n实付：20.00\n收货地址：深圳市龙华区人民路\n联系电话：13800138000\n测试充电器",
            preserveAddress = false,
        )
        assertFalse(result.rawText.contains("人民路"))
        assertFalse(result.rawText.contains("13800138000"))
        assertTrue(result.order.shippingAddress.isBlank())
        assertFalse(result.keepAddress)
    }

    @Test
    fun parsesDouyinLuckyBagAsPrizeWithoutInventingPaidAmount() {
        val result = OrderParser.parse(
            """
            喵梵思宠物食品旗舰店音旗舰 国直播>
            奖品编号
            交易快照
            领奖时间
            发货时间
            成交时间
            领奖完成
            【福袋】主食迷你罐爆珠50g*2 ¥0.00
            |超级福袋奖品
            6900000000000000012 复制
            x1
            2026-09-05 18:28:13
            2026-09-06 08:39:54
            """.trimIndent(),
            "com.ss.android.ugc.livelite",
        )

        assertEquals("order", result.kind)
        assertEquals("抖省省", result.order.platform)
        assertEquals("领奖完成", result.order.status)
        assertEquals("6900000000000000012", result.order.orderNumber)
        assertNull(result.order.totalPaid)
        assertEquals("2026-09-05 18:28:13", result.order.orderedAt)
        assertEquals("【福袋】主食迷你罐爆珠50g*2", result.order.items.first().name)
        assertTrue(result.warnings.any { it.contains("福袋奖品") })
    }

    @Test
    fun cleansDouyinOcrMerchantBadgeAndImagePrefix() {
        val result = OrderParser.parse(
            """
            く  交易完成
            喵梵思宠物食品舰店 抖旗舰] 直播>
            99.9%A 让养好不再成力合 5.9折) 【请仓5.9折 宠物磷虾油】喵... ¥29.44
            宠物南极磷虾油15ml(2026年1月生产) x1
            商品总价 ¥29.44
            金币抵扣 -¥7.57
            实付款 ¥17.68
            订单编号 6900000000000000010复制
            下单时间 2026-09-07 13:35:27
            """.trimIndent(),
            "com.ss.android.ugc.livelite",
            fromOcr = true,
        )

        assertEquals("喵梵思宠物食品旗舰店", result.order.merchant)
        assertEquals("【请仓5.9折 宠物磷虾油】喵...", result.order.items.first().name)
        assertFalse(result.order.items.first().name.contains("99.9%"))
    }

    @Test
    fun findsSuffixlessDouyinMerchantAndDoesNotUseDiscountAsProduct() {
        val result = OrderParser.parse(
            """
            く 交易完成
            蔚错优品严选
            可折卸磁兹吸类纸膜 进口磁力iPad磁吸类纸膜Air6... 5.20
            强磁吸附【磁吸类纸膜】随用随 x1
            商品总价 ¥5.20
            金币抵扣 -¥1.33
            实付款 ¥3.12
            订单编号 6900000000000000013复制
            """.trimIndent(),
            "com.ss.android.ugc.livelite",
        )

        assertEquals("蔚错优品严选", result.order.merchant)
        assertTrue(result.order.items.first().name.contains("磁吸类纸膜"))
        assertFalse(result.order.items.first().name.contains("金币抵扣"))
    }

    @Test
    fun appliesPersistentPlatformOverrideToDouyinParserFamily() {
        val result = OrderParser.parse(
            "交易完成\n测试店铺>\n测试商品 9.90\n实付款 ¥9.90\n订单编号 6900000000000000013复制",
            "com.unknown.shop",
            platformOverride = "抖省省",
        )

        assertEquals("抖省省", result.order.platform)
        assertEquals("测试店铺", result.order.merchant)
        assertEquals("测试商品", result.order.items.first().name)
    }

    @Test
    fun recognizesUsedDouyinVoucherDespiteOcrStatusTypoWithoutInventingMerchant() {
        val result = OrderParser.parse(
            """
            客服
            交易咸功
            感谢购买,期待再次光临
            【暑假】盒马水牛乳米布...
            20
            |到店自提·非邮寄免预约
            水牛乳米布]
            周一至周日可用 过期自动退
            价9.9 9.9 /份
            已便用(1)
            商品/服务体验是否满意?
            盒马鲜生甄选
            粉丝:178.2w 作品:1950
            打开团购详情,按钮
            查看券码详情,按钮
            """.trimIndent(),
            "com.ss.android.ugc.livelite",
        )

        assertEquals("order", result.kind)
        assertEquals("抖省省", result.order.platform)
        assertEquals("交易成功", result.order.status)
        assertEquals("【暑假】盒马水牛乳米布...", result.order.items.first().name)
        assertTrue(result.order.merchant.isBlank())
    }
}
