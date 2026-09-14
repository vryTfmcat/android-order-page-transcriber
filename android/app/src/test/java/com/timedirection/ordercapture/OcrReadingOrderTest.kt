package com.timedirection.ordercapture

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrReadingOrderTest {
    @Test
    fun joinsColumnsOnTheSameVisualRowBeforeMovingDown() {
        val result = OcrReadingOrder.assemble(
            listOf(
                OcrReadingOrder.Segment("商品总价", 30, 100, 170, 140),
                OcrReadingOrder.Segment("¥49.90", 800, 102, 920, 142),
                OcrReadingOrder.Segment("实付款", 30, 210, 170, 250),
                OcrReadingOrder.Segment("¥25.85", 800, 207, 920, 247),
                OcrReadingOrder.Segment("订单编号", 30, 310, 170, 350),
                OcrReadingOrder.Segment("6900000000000000011", 520, 313, 910, 353),
            ),
        )

        assertEquals(
            "商品总价  ¥49.90\n实付款  ¥25.85\n订单编号  6900000000000000011",
            result,
        )
    }
}
