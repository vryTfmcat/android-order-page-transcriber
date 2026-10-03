package com.timedirection.ordercapture

import java.util.Locale

/**
 * 平台识别规则。
 *
 * 采集层保持通用，差异只放在这些配置中：App 包名、页面特征、字段标签和状态词。
 * 手动切换时直接使用指定配置；自动模式则按特征得分选择。
 */
data class PlatformProfile(
    val id: String,
    val label: String,
    val platform: String,
    val packageHints: List<String>,
    val urlHints: List<String> = emptyList(),
    val textHints: List<String>,
    val strongTextHints: List<String> = emptyList(),
    val orderNumberLabels: List<String> = listOf("订单号", "订单编号", "商家单号"),
    val actualPaidLabels: List<String> = listOf("实付款", "实付", "付款金额"),
    val merchantLabels: List<String> = listOf("店铺", "商家", "门店"),
    val statusWords: List<String> = emptyList(),
    val orderHints: List<String> = emptyList(),
) {
    fun score(packageName: String, text: String, url: String): Int {
        val source = "$packageName $url".lowercase(Locale.ROOT)
        var score = 0
        if (packageHints.any(source::contains)) score += 5
        if (urlHints.any(source::contains)) score += 4
        score += textHints.count(text::contains) * 2
        score += strongTextHints.count(text::contains) * 4
        return score
    }
}

object PlatformProfiles {
    const val AUTO_LABEL = "自动识别"

    val all: List<PlatformProfile> = listOf(
        PlatformProfile(
            id = "pinduoduo",
            label = "拼多多规则",
            platform = "拼多多",
            packageHints = listOf("pinduoduo", "yangkeduo", "xunmeng"),
            urlHints = listOf("yangkeduo.com", "pinduoduo.com"),
            textHints = listOf("拼多多", "多多支付", "拼小圈", "再次拼单"),
            orderNumberLabels = listOf("订单编号", "订单号"),
            actualPaidLabels = listOf("实付", "实付款", "实付金额", "支付金额"),
            merchantLabels = listOf("店铺", "商家"),
            statusWords = listOf("拼团中", "拣货", "已按时发货", "待收货"),
            orderHints = listOf("多多支付", "再次拼单"),
        ),
        PlatformProfile(
            id = "douyin_groupbuy",
            label = "抖音团购规则",
            platform = "抖音团购",
            packageHints = listOf("aweme", "douyin"),
            textHints = listOf("抖音", "抖音支付"),
            strongTextHints = listOf("团购订单", "团购详情", "券码详情", "到店使用", "适用门店", "待到店使用"),
            orderNumberLabels = listOf("订单编号", "订单号", "团购订单号"),
            actualPaidLabels = listOf("实付金额", "实付款", "实付", "付款金额"),
            merchantLabels = listOf("门店名称", "商家名称", "门店", "商家"),
            statusWords = listOf("待使用", "待到店使用", "已使用", "已过期", "退款成功"),
            orderHints = listOf("券码详情", "使用须知", "适用门店"),
        ),
        PlatformProfile(
            id = "douyin_mall",
            label = "抖音商城规则",
            platform = "抖音商城",
            packageHints = listOf("aweme", "douyin"),
            textHints = listOf("抖音商城", "来自抖音", "抖音支付", "抖音月付"),
            strongTextHints = listOf("商品总价", "确认收货后付款", "交易快照"),
            orderNumberLabels = listOf("订单编号", "订单号"),
            actualPaidLabels = listOf("实付款", "实付", "付款金额"),
            merchantLabels = listOf("店铺", "商家"),
            statusWords = listOf("打包中", "运输中", "待收货", "交易完成", "交易关闭"),
            orderHints = listOf("确认收货后付款", "交易快照"),
        ),
        PlatformProfile(
            id = "meituan",
            label = "美团规则",
            platform = "美团",
            packageHints = listOf("sankuai.meituan", "meituan"),
            urlHints = listOf("meituan.com"),
            textHints = listOf("美团", "美团支付", "美团外卖"),
            orderNumberLabels = listOf("订单号码", "订单号", "订单编号"),
            actualPaidLabels = listOf("实付", "实付款", "支付金额", "合计"),
            merchantLabels = listOf("商家", "门店", "店铺"),
            statusWords = listOf("商家已接单", "骑手配送中", "订单已送达", "待使用", "已使用"),
            orderHints = listOf("预计送达", "骑手", "取餐码"),
        ),
        PlatformProfile(
            id = "eleme",
            label = "饿了么规则",
            platform = "饿了么",
            packageHints = listOf("me.ele", "eleme"),
            urlHints = listOf("ele.me", "eleme.cn"),
            textHints = listOf("饿了么", "蜂鸟专送", "蓝骑士"),
            orderNumberLabels = listOf("订单号", "订单编号"),
            actualPaidLabels = listOf("实付", "实付款", "订单实付", "支付金额"),
            merchantLabels = listOf("商家", "店铺", "门店"),
            statusWords = listOf("商家已接单", "配送中", "订单已送达", "订单已完成", "已取消"),
            orderHints = listOf("蜂鸟专送", "蓝骑士", "预计送达"),
        ),
        PlatformProfile(
            id = "taobao",
            label = "淘宝规则",
            platform = "淘宝",
            packageHints = listOf("taobao.taobao", "tmall"),
            urlHints = listOf("taobao.com", "tmall.com"),
            textHints = listOf("淘宝", "天猫", "支付宝"),
            orderNumberLabels = listOf("订单编号", "订单号"),
            actualPaidLabels = listOf("实付款", "实付", "付款金额"),
            merchantLabels = listOf("店铺名称", "店铺", "卖家"),
            statusWords = listOf("卖家已发货", "买家已付款", "交易成功", "交易关闭"),
            orderHints = listOf("交易快照", "查看物流"),
        ),
        PlatformProfile(
            id = "jd",
            label = "京东规则",
            platform = "京东",
            packageHints = listOf("jingdong.app.mall", "jd.app"),
            urlHints = listOf("jd.com"),
            textHints = listOf("京东", "京东物流", "京东支付"),
            orderNumberLabels = listOf("订单编号", "订单号"),
            actualPaidLabels = listOf("实付款", "实付", "支付金额", "在线支付"),
            merchantLabels = listOf("店铺", "销售商", "商家"),
            statusWords = listOf("正在出库", "等待收货", "已完成", "已取消"),
            orderHints = listOf("京东物流", "配送员"),
        ),
        PlatformProfile(
            id = "idlefish",
            label = "闲鱼规则",
            platform = "闲鱼",
            packageHints = listOf("taobao.idlefish", "idlefish"),
            urlHints = listOf("2.taobao.com", "goofish.com"),
            textHints = listOf("闲鱼", "卖家昵称", "我买到的"),
            orderNumberLabels = listOf("订单号", "订单编号", "交易号"),
            actualPaidLabels = listOf("成交价", "实付", "实付款", "付款金额"),
            merchantLabels = listOf("卖家昵称", "卖家"),
            statusWords = listOf("等待卖家发货", "卖家已发货", "交易成功", "交易关闭"),
            orderHints = listOf("我买到的", "我卖出的", "聊一聊"),
        ),
        // 保留现有已使用的扩展平台，不影响本次要求的八个主配置。
        PlatformProfile(
            id = "dou_sheng_sheng", label = "抖省省规则", platform = "抖省省",
            packageHints = listOf("ugc.livelite"), textHints = listOf("抖省省"),
        ),
        PlatformProfile(
            id = "xiaomi_mall", label = "小米商城规则", platform = "小米商城",
            packageHints = listOf("xiaomi.shop", "xiaomi"), textHints = listOf("小米商城"),
        ),
    )

    val options: List<String> = listOf(AUTO_LABEL) + all.map { it.label }

    fun bySelection(value: String): PlatformProfile? {
        val normalized = value.trim()
        if (normalized.isBlank() || normalized == AUTO_LABEL) return null
        return all.firstOrNull { it.id == normalized || it.label == normalized || it.platform == normalized }
    }

    fun resolve(selection: String, packageName: String, text: String, url: String): PlatformProfile? =
        bySelection(selection) ?: detect(packageName, text, url)

    fun detect(packageName: String, text: String, url: String = ""): PlatformProfile? {
        val groupbuy = all.first { it.id == "douyin_groupbuy" }
        if (groupbuy.strongTextHints.any(text::contains) &&
            (groupbuy.packageHints.any(packageName.lowercase(Locale.ROOT)::contains) || text.contains("抖音"))
        ) return groupbuy
        val scored = all.map { profile ->
            val score = profile.score(packageName, text, url) - if (profile.id == "douyin_groupbuy") 5 else 0
            profile to score
        }
        val best = scored.maxByOrNull { it.second } ?: return null
        return best.first.takeIf { best.second >= 2 }
    }
}
