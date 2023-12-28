package com.trading.bot.logic;

import com.trading.bot.configuration.MovingStrategy;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.trade.StopOrder;
import org.knowm.xchange.kucoin.KucoinMarketDataService;
import org.knowm.xchange.kucoin.dto.response.KucoinKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Strategy;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

import static com.trading.bot.configuration.BotConfig.CURRENCY_PAIR;
import static org.knowm.xchange.dto.Order.OrderType.ASK;
import static org.knowm.xchange.dto.Order.OrderType.BID;
import static org.knowm.xchange.kucoin.dto.KlineIntervalType.min5;


@Service
public class ExchangeTrader implements Trader {

    @Value("${trader.buylimit}")
    public BigDecimal tradeLimit;

    @Value("${trader.stopOrderPercent}")
    private BigDecimal stopOrderPercent;

    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    private final Exchange exchange;
    private Order.OrderType tradeStatus = ASK;
    public String orderId = "";
    public final BarSeries barSeries;
    private final Strategy strategy;
    private BigDecimal bidOrderPercent;
    private BigDecimal askOrderPercent;
    private BigDecimal askOrderPrice;

    public ExchangeTrader(Exchange exchange) {
        this.exchange = exchange;
        barSeries = new BaseBarSeries();
        strategy = MovingStrategy.buildStrategy(barSeries);
    }

    @PostConstruct
    public void postConstruct() throws IOException {
        final long startDate = LocalDateTime.now(ZoneOffset.UTC).minusDays(4).toEpochSecond(ZoneOffset.UTC);
        final long endDate = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES).toEpochSecond(ZoneOffset.UTC);

        final List<KucoinKline> kucoinKlines = ((KucoinMarketDataService) exchange.getMarketDataService())
                .getKucoinKlines(CURRENCY_PAIR, startDate, endDate, min5);
        Collections.reverse(kucoinKlines);
        kucoinKlines.forEach(this::loadBarSeries);

        bidOrderPercent = BigDecimal.valueOf(100).add(stopOrderPercent).multiply(BigDecimal.valueOf(0.01));
        askOrderPercent = BigDecimal.valueOf(100).subtract(stopOrderPercent).multiply(BigDecimal.valueOf(0.01));
    }


    public void next(KucoinKline lastKline) throws IOException {
        loadBarSeries(lastKline);

        if (tradeStatus.equals(ASK)) {
            tradeBID(lastKline);
            return;
        }

        if (tradeStatus.equals(BID)) {
            tradeASK(lastKline);
        }
    }

    private void tradeBID(KucoinKline lastKline) throws IOException {
        BigDecimal baseBalance = getBalance();
        if (baseBalance.compareTo(tradeLimit) >= 0) {
            // Buy
            logger.info("BID StopOrder {} submitted, change to IN_BID", orderId);
            tradeStatus = BID;
        } else {
            if (!orderId.isEmpty()) {
                // Cancel
                cancelOrder();
            }

            if (strategy.shouldEnter(barSeries.getEndIndex())) {
                // New
                BigDecimal stopOrderPrice = lastKline.getOpen().multiply(bidOrderPercent);
                stopOrderPrice = stopOrderPrice.compareTo(lastKline.getHigh()) > 0 ? stopOrderPrice : lastKline.getHigh();
                placeStopOrder(BID, stopOrderPrice);
                logger.info("StopOrder BID placed {} Price {} Response {}", tradeLimit, stopOrderPrice, orderId);
            }
        }
    }

    private void tradeASK(KucoinKline lastKline) throws IOException {
        BigDecimal baseBalance = getBalance();
        if (baseBalance.compareTo(tradeLimit) < 0) {
            // Sell
            logger.info("ASK StopOrder {} submitted, change to IN_ASK", orderId);
            tradeStatus = ASK;
            askOrderPrice = BigDecimal.ZERO;
        } else {

            // Calc Stop Loss
            BigDecimal stopOrderPrice = lastKline.getOpen().multiply(askOrderPercent);
            stopOrderPrice = stopOrderPrice.compareTo(lastKline.getLow()) > 0 ? stopOrderPrice : lastKline.getLow();

            if (stopOrderPrice.compareTo(askOrderPrice) > 0) {
                askOrderPrice = stopOrderPrice;

                if (!orderId.isEmpty()) {
                    cancelOrder();
                }

                // New
                placeStopOrder(ASK, askOrderPrice);
                logger.info("ASK StopOrder ASK placed {} Price {} Response {}", tradeLimit, askOrderPrice, orderId);
            }
        }
    }

    public void loadBarSeries(KucoinKline kucoinKlines) {
        if (barSeries.isEmpty() || kucoinKlines.getTime() > barSeries.getLastBar().getEndTime().toEpochSecond()) {
            barSeries.addBar(Duration.ofMinutes(5L),
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(kucoinKlines.getTime()), ZoneOffset.UTC),
                    kucoinKlines.getOpen(),
                    kucoinKlines.getHigh(),
                    kucoinKlines.getLow(),
                    kucoinKlines.getClose(),
                    kucoinKlines.getVolume());
        }
    }

    @Override
    public BigDecimal getBalance() throws IOException {
        return exchange.getAccountService().getAccountInfo().getWallet("trade").getBalance(Currency.SOL).getAvailable();
    }

    @Override
    public void placeStopOrder(Order.OrderType bid, BigDecimal stopOrderPrice) throws IOException {
        StopOrder stopOrder = new StopOrder(bid, tradeLimit, CURRENCY_PAIR, "", null, stopOrderPrice);
        orderId = exchange.getTradeService().placeStopOrder(stopOrder);
    }

    @Override
    public void cancelOrder() throws IOException {
        exchange.getTradeService().cancelOrder(orderId);
        logger.info("StopOrder {} canceled", orderId);
        orderId = "";
    }
}
