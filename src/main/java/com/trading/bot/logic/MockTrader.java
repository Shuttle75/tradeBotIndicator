package com.trading.bot.logic;

import lombok.Data;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.kucoin.KucoinMarketDataService;
import org.knowm.xchange.kucoin.dto.response.KucoinKline;

import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.trading.bot.configuration.BotConfig.CURRENCY_PAIR;
import static org.knowm.xchange.dto.Order.OrderType.ASK;
import static org.knowm.xchange.dto.Order.OrderType.BID;
import static org.knowm.xchange.kucoin.dto.KlineIntervalType.min5;

@Service
public class MockTrader extends ExchangeTrader {

    private final Exchange exchange;
    private StopOrder stopOrder;
    private BigDecimal balance;


    public MockTrader(Exchange exchange) {
        super(exchange);
        this.exchange = exchange;
    }

    @PostConstruct
    public void postConstruct() throws IOException {
        final long startDate = LocalDateTime.now(ZoneOffset.UTC).minusDays(4).toEpochSecond(ZoneOffset.UTC);
        final long endDate = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES).toEpochSecond(ZoneOffset.UTC);

        final List<KucoinKline> kucoinKlines = ((KucoinMarketDataService) exchange.getMarketDataService())
                .getKucoinKlines(CURRENCY_PAIR, startDate, endDate, min5);
        Collections.reverse(kucoinKlines);
        kucoinKlines.forEach(this::loadBarSeries);
    }

    @Override
    public void loadBarSeries(KucoinKline kline) {
        if (stopOrder != null) {
            if (stopOrder.orderType.equals(BID) && stopOrder.price.compareTo(kline.getHigh()) < 0 && stopOrder.price.compareTo(kline.getLow()) > 0) {
                balance = tradeLimit;
            }
            if (stopOrder.orderType.equals(ASK) && stopOrder.price.compareTo(kline.getHigh()) < 0 && stopOrder.price.compareTo(kline.getLow()) > 0) {
                balance = BigDecimal.ZERO;
            }
        }

        if (barSeries.isEmpty() || kline.getTime() > barSeries.getLastBar().getEndTime().toEpochSecond()) {
            barSeries.addBar(Duration.ofMinutes(5L),
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(kline.getTime()), ZoneOffset.UTC),
                    kline.getOpen(),
                    kline.getHigh(),
                    kline.getLow(),
                    kline.getClose(),
                    kline.getVolume());
        }
    }

    @Override
    public BigDecimal getBalance() {
        return balance;
    }

    @Override
    public void placeStopOrder(Order.OrderType bid, BigDecimal stopOrderPrice) {
        orderId = UUID.randomUUID().toString();
        stopOrder = new StopOrder(bid, stopOrderPrice);
    }

    @Override
    public void cancelOrder() {
        orderId = "";
        stopOrder = null;
    }

    @Data
    private static class StopOrder {

        public StopOrder(Order.OrderType orderType, BigDecimal price) {
            this.price = price;
            this.orderType = orderType;
        }

        private BigDecimal price;
        private Order.OrderType orderType;
    }
}


