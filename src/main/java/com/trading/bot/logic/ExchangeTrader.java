package com.trading.bot.logic;

import com.trading.bot.configuration.MovingStrategy;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.trade.StopOrder;
import org.knowm.xchange.kucoin.dto.response.KucoinKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Strategy;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;

import static com.trading.bot.configuration.BotConfig.CURRENCY_PAIR;
import static org.knowm.xchange.dto.Order.OrderType.ASK;
import static org.knowm.xchange.dto.Order.OrderType.BID;

@Profile("prod")
@Service
public class ExchangeTrader implements Trader {

    @Value("${trader.buylimit}")
    public BigDecimal tradeLimit;

  //  @Value("${trader.stopOrderPercent}")
    private BigDecimal stopOrderPercent = BigDecimal.valueOf(0.5F);

    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    private final Exchange exchange;
    private Order.OrderType tradeStatus = ASK;
    public String orderId = "";
    public final BarSeries barSeries;
    private final Strategy strategy;
    private BigDecimal bidOrderPercent;
    private BigDecimal askOrderPercent;
    private BigDecimal askOrderPrice = BigDecimal.ZERO;

    public ExchangeTrader(Exchange exchange) {
        this.exchange = exchange;
        barSeries = new BaseBarSeries();
        strategy = MovingStrategy.buildStrategy(barSeries);
    }

    @PostConstruct
    public void postConstruct() {
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
        if (baseBalance.compareTo(BigDecimal.ONE) >= 0) {
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
                BigDecimal stopOrderPrice = lastKline.getClose().multiply(bidOrderPercent);
                stopOrderPrice = stopOrderPrice.compareTo(lastKline.getHigh()) > 0 ? stopOrderPrice : lastKline.getHigh();
                placeStopOrder(BID, stopOrderPrice);
                logger.info("StopOrder BID placed {} Price {} Response {}", tradeLimit, stopOrderPrice, orderId);
            }
        }
    }

    private void tradeASK(KucoinKline lastKline) throws IOException {
        BigDecimal baseBalance = getBalance();
        if (baseBalance.compareTo(BigDecimal.ONE) < 0) {
            // Sell
            logger.info("ASK StopOrder {} submitted, change to IN_ASK", orderId);
            tradeStatus = ASK;
            askOrderPrice = BigDecimal.ZERO;
        } else {

            // Calc Stop Loss
            BigDecimal stopOrderPrice = lastKline.getClose().multiply(askOrderPercent);
            stopOrderPrice = stopOrderPrice.compareTo(lastKline.getLow()) < 0 ? stopOrderPrice : lastKline.getLow();

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

    public void loadBarSeries(KucoinKline kline) {
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
    public BigDecimal getBalance() throws IOException {
        return exchange.getAccountService().getAccountInfo().getWallet("trade").getBalance(Currency.SOL).getAvailable();
    }

    @Override
    public void placeStopOrder(Order.OrderType bid, BigDecimal stopOrderPrice) throws IOException {
        StopOrder stopOrder = new StopOrder(bid, tradeLimit, CURRENCY_PAIR, "", null, stopOrderPrice);
//        orderId = exchange.getTradeService().placeStopOrder(stopOrder);
    }

    @Override
    public void cancelOrder() throws IOException {
        exchange.getTradeService().cancelOrder(orderId);
        logger.info("StopOrder {} canceled", orderId);
        orderId = "";
    }
}
