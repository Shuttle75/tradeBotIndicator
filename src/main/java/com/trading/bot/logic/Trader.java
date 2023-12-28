package com.trading.bot.logic;

import org.knowm.xchange.dto.Order;
import org.knowm.xchange.kucoin.dto.response.KucoinKline;

import java.io.IOException;
import java.math.BigDecimal;

public interface Trader {
    void loadBarSeries(KucoinKline kucoinKlines);
    void next(KucoinKline lastKline) throws IOException;
    BigDecimal getBalance() throws IOException;
    void placeStopOrder(Order.OrderType bid, BigDecimal stopOrderPrice) throws IOException;
    void cancelOrder() throws IOException;
}
