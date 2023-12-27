package com.trading.bot.scheduler;

import com.trading.bot.configuration.MovingStrategy;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.dto.trade.StopOrder;
import org.knowm.xchange.kucoin.KucoinMarketDataService;
import org.knowm.xchange.kucoin.dto.response.KucoinKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Strategy;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.trading.bot.configuration.BotConfig.CURRENCY_PAIR;
import static com.trading.bot.scheduler.TradeStatus.*;
import static org.knowm.xchange.dto.Order.OrderType.*;
import static org.knowm.xchange.kucoin.dto.KlineIntervalType.min5;

enum TradeStatus{READY_FOR_BID, IN_BID, READY_FOR_ASK, IN_ASK}

@Service
public class Trader {
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    private final Exchange exchange;
    private TradeStatus tradeStatus = IN_ASK;
    private String orderId = "";
    private final BarSeries barSeries;
    private final Strategy strategy;

    @Value("${trader.buylimit}")
    public BigDecimal tradeLimit;

    public Trader(Exchange exchange) {
        this.exchange = exchange;
        barSeries = new BaseBarSeries();
        strategy = MovingStrategy.buildStrategy(barSeries);
    }

    @PostConstruct
    public void postConstruct() throws IOException {
        final long startDate = LocalDateTime.now(ZoneOffset.UTC).minusDays(4).toEpochSecond(ZoneOffset.UTC);
        final long endDate = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES)
            .minusMinutes(10).toEpochSecond(ZoneOffset.UTC);

        final List<KucoinKline> kucoinKlines = ((KucoinMarketDataService) exchange.getMarketDataService())
                .getKucoinKlines(CURRENCY_PAIR, startDate, endDate, min5);
        Collections.reverse(kucoinKlines);
        kucoinKlines.forEach(kucoinKline -> loadBarSeries(barSeries, kucoinKline));
    }

   @Scheduled(cron = "30 */5 * * * *")
    public void sell() throws IOException {
        final long startDate = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30).toEpochSecond(ZoneOffset.UTC);
        List<KucoinKline> kucoinKlines = ((KucoinMarketDataService) exchange.getMarketDataService())
                .getKucoinKlines(CURRENCY_PAIR, startDate, 0L, min5);
        KucoinKline lastKline = kucoinKlines.get(1);
        loadBarSeries(barSeries, lastKline);

        if (tradeStatus.equals(IN_ASK) && strategy.shouldEnter(barSeries.getEndIndex())) {
            BigDecimal stopOrderPrice = lastKline.getClose().multiply(BigDecimal.valueOf(1.005));
            StopOrder stopOrder =
                    new StopOrder(BID, tradeLimit, CURRENCY_PAIR, "", null, stopOrderPrice);
            orderId = exchange.getTradeService().placeStopOrder(stopOrder);

            logger.info("StopOrder BID placed {} Price {} Response {}", tradeLimit, stopOrderPrice, orderId);

            tradeStatus = READY_FOR_BID;
            return;
        }

        if (tradeStatus.equals(READY_FOR_BID) && !orderId.isEmpty()) {
            BigDecimal baseBalance = exchange.getAccountService().getAccountInfo().getWallet("trade").getBalance(Currency.SOL).getAvailable();

            if (baseBalance.compareTo(tradeLimit) >= 0) {
                logger.info("StopOrder {} submitted", orderId);

                tradeStatus = IN_BID;
            } else {
                exchange.getTradeService().cancelOrder(orderId);
                logger.info("StopOrder {} canceled", orderId);

                tradeStatus = IN_ASK;
            }

            orderId = "";
            return;
        }

        if (tradeStatus.equals(IN_BID) && strategy.shouldExit(barSeries.getEndIndex())) {
            BigDecimal stopOrderPrice = lastKline.getClose().multiply(BigDecimal.valueOf(0.995));
            StopOrder stopOrder =
                    new StopOrder(ASK, tradeLimit, CURRENCY_PAIR, "", null, stopOrderPrice);
            orderId = exchange.getTradeService().placeStopOrder(stopOrder);

            logger.info("StopOrder ASK placed {} Price {} Response {}", tradeLimit, stopOrderPrice, orderId);

            tradeStatus = READY_FOR_ASK;
            return;
        }

        if (tradeStatus.equals(READY_FOR_ASK) && !orderId.isEmpty()) {
            BigDecimal baseBalance = exchange.getAccountService().getAccountInfo().getWallet("trade").getBalance(Currency.SOL).getAvailable();

            if (baseBalance.compareTo(tradeLimit) < 0) {
                logger.info("StopOrder {} submitted", orderId);
                tradeStatus = IN_ASK;
            } else {
                exchange.getTradeService().cancelOrder(orderId);
                logger.info("StopOrder {} canceled", orderId);

                tradeStatus = IN_BID;
            }

            orderId = "";
        }
    }

    public static void loadBarSeries(BarSeries barSeries, KucoinKline kucoinKlines) {
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
}
