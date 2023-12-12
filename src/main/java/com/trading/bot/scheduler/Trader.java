package com.trading.bot.scheduler;

import com.trading.bot.configuration.MovingMomentumStrategy;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.kucoin.KucoinMarketDataService;
import org.knowm.xchange.kucoin.dto.KlineIntervalType;
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
import static org.knowm.xchange.dto.Order.OrderType.*;

@Service
public class Trader {
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    private final Exchange exchange;
    private boolean purchased = false;
    private String orderId;
    private final BarSeries barSeries;
    private final Strategy strategy;

    @Value("${trader.buylimit}")
    public BigDecimal tradeLimit;

    public Trader(Exchange exchange) {
        this.exchange = exchange;
        barSeries = new BaseBarSeries();
        strategy = MovingMomentumStrategy.buildStrategy(barSeries);
    }

    @PostConstruct
    public void postConstruct() throws IOException {
        final long startDate = LocalDateTime.now(ZoneOffset.UTC).minusDays(4).toEpochSecond(ZoneOffset.UTC);
        final long endDate = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES)
            .minusMinutes(5).toEpochSecond(ZoneOffset.UTC);

        final List<KucoinKline> kucoinKlines = getKucoinKlines(exchange, startDate, endDate);
        Collections.reverse(kucoinKlines);
        kucoinKlines.forEach(kucoinKline -> loadBarSeries(barSeries, kucoinKline));
    }

    @Scheduled(cron = "10 *AVAX * * * *")
    public void sell() throws IOException {
        final long startDate = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30).toEpochSecond(ZoneOffset.UTC);
        List<KucoinKline> kucoinKlines = getKucoinKlines(exchange, startDate, 0L);
        KucoinKline lastKline = kucoinKlines.get(1);
        loadBarSeries(barSeries, lastKline);

        if (!purchased && strategy.shouldEnter(barSeries.getEndIndex())) {
            MarketOrder marketOrder = new MarketOrder(BID, tradeLimit, CURRENCY_PAIR);
            orderId = exchange.getTradeService().placeMarketOrder(marketOrder);

            logger.info("BUY {} Price {} Response {}", tradeLimit, lastKline.getClose(), orderId);
            purchased = true;
            return;
        }

        if (purchased && strategy.shouldExit(barSeries.getEndIndex())) {
            //exchange.getAccountService().getAccountInfo().getWallet("AVAX").getBalance()
            MarketOrder marketOrder = new MarketOrder(ASK, tradeLimit, CURRENCY_PAIR);
            orderId = exchange.getTradeService().placeMarketOrder(marketOrder);

            logger.info("SELL {} Price {} Response {}", tradeLimit, lastKline.getClose(), orderId);
            purchased = false;
        }
    }

    public static List<KucoinKline> getKucoinKlines(Exchange exchange, long startDate, long endDate) throws IOException {
        return ((KucoinMarketDataService) exchange.getMarketDataService())
                .getKucoinKlines(CURRENCY_PAIR, startDate, endDate, KlineIntervalType.min1);
    }

    public static void loadBarSeries(BarSeries barSeries, KucoinKline kucoinKlines) {
        barSeries.addBar(Duration.ofMinutes(5L),
                ZonedDateTime.ofInstant(Instant.ofEpochSecond(kucoinKlines.getTime()), ZoneOffset.UTC),
                kucoinKlines.getOpen(),
                kucoinKlines.getHigh(),
                kucoinKlines.getLow(),
                kucoinKlines.getClose(),
                kucoinKlines.getVolume());
    }
}
