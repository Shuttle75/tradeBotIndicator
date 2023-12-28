package com.trading.bot.logic;

import lombok.Builder;
import lombok.Data;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.kucoin.dto.response.KucoinKline;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.knowm.xchange.dto.Order.OrderType.BID;

@Profile("test")
@Service
public class MockTrader extends ExchangeTrader {

    private StopOrder stopOrderBID;
    private StopOrder stopOrderASK;
    public BigDecimal walletUSDT;
    private BigDecimal walletBase = BigDecimal.valueOf(0);
    public List<String> listResult = new ArrayList<>();

    public MockTrader(Exchange exchange) {
        super(exchange);
        stopOrderBID = StopOrder.builder().price(BigDecimal.ZERO).build();
        stopOrderASK = StopOrder.builder().price(BigDecimal.ZERO).build();
    }

    @Override
    public void loadBarSeries(KucoinKline kline) {
        if (stopOrderBID.active
                && stopOrderBID.price.compareTo(kline.getHigh()) < 0
                && stopOrderBID.price.compareTo(kline.getLow()) > 0) {
            stopOrderBID.setDate(kline.getTime());
            stopOrderBID.setWalletUSDT(walletUSDT);

            walletBase = walletUSDT.divide(kline.getClose(), 0, RoundingMode.DOWN);
            walletUSDT = walletUSDT.subtract(walletBase.multiply(kline.getClose()));

            stopOrderBID.setActive(false);
        }

        if (stopOrderASK.active
                && stopOrderASK.price.compareTo(kline.getHigh()) < 0
                && stopOrderASK.price.compareTo(kline.getLow()) > 0) {
            stopOrderASK.setDate(kline.getTime());

            walletUSDT = walletUSDT.add(walletBase.multiply(kline.getClose()));
            walletBase = BigDecimal.valueOf(0);

            stopOrderASK.setWalletUSDT(walletUSDT);
            stopOrderASK.setActive(false);

            listResult.add(ZonedDateTime.ofInstant(Instant.ofEpochSecond(stopOrderBID.date), ZoneOffset.UTC) + " " +
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(stopOrderASK.date), ZoneOffset.UTC) + "   " +
                    new DecimalFormat("#0.000").format(stopOrderBID.price.doubleValue()) + " " +
                    new DecimalFormat("#0.000").format(stopOrderASK.price.doubleValue()) + "   " +
                    new DecimalFormat("#0.00").format(stopOrderBID.walletUSDT.doubleValue()) + " " +
                    new DecimalFormat("#0.00").format(stopOrderASK.walletUSDT.doubleValue()) + " " +
                    new DecimalFormat("#0.00").format(stopOrderASK.walletUSDT.subtract(stopOrderBID.walletUSDT).doubleValue()));
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
        return walletBase;
    }

    @Override
    public void placeStopOrder(Order.OrderType bid, BigDecimal stopOrderPrice) {
        orderId = UUID.randomUUID().toString();
        if (bid.equals(BID)) {
            stopOrderBID = StopOrder.builder().price(stopOrderPrice).active(true).build();
        } else {
            stopOrderASK = StopOrder.builder().price(stopOrderPrice).active(true).build();
        }
    }

    @Override
    public void cancelOrder() {
        orderId = "";
        stopOrderBID.setActive(false);
        stopOrderASK.setActive(false);
    }

    @Data
    @Builder
    private static class StopOrder {
        private long date;
        private BigDecimal price;
        private BigDecimal walletUSDT;
        private boolean active;
    }
}


