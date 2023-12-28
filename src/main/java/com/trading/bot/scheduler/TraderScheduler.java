package com.trading.bot.scheduler;

import com.trading.bot.logic.Trader;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.kucoin.KucoinMarketDataService;
import org.knowm.xchange.kucoin.dto.response.KucoinKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.trading.bot.configuration.BotConfig.CURRENCY_PAIR;
import static org.knowm.xchange.kucoin.dto.KlineIntervalType.min5;

@Profile("prod")
@Service
public class TraderScheduler {
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    private final Exchange exchange;
    private final Trader trader;

    public TraderScheduler(Trader trader, Exchange exchange) {
        this.exchange = exchange;
        this.trader = trader;
    }

    @PostConstruct
    public void postConstruct() throws IOException {
        final long startDate = LocalDateTime.now(ZoneOffset.UTC).minusDays(4).toEpochSecond(ZoneOffset.UTC);
        final long endDate = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES).toEpochSecond(ZoneOffset.UTC);

        final List<KucoinKline> kucoinKlines = ((KucoinMarketDataService) exchange.getMarketDataService())
                .getKucoinKlines(CURRENCY_PAIR, startDate, endDate, min5);
        Collections.reverse(kucoinKlines);
        kucoinKlines.forEach(trader::loadBarSeries);
    }

   @Scheduled(cron = "30 */5 * * * *")
    public void sell() throws IOException {
        final long startDate = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30).toEpochSecond(ZoneOffset.UTC);
        List<KucoinKline> kucoinKlines = ((KucoinMarketDataService) exchange.getMarketDataService())
                .getKucoinKlines(CURRENCY_PAIR, startDate, 0L, min5);
        KucoinKline lastKline = kucoinKlines.get(1);
       trader.loadBarSeries(lastKline);

       trader.next(lastKline);

    }
}
