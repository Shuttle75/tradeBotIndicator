package com.trading.bot.controllers;

import com.trading.bot.logic.MockTrader;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.kucoin.KucoinMarketDataService;
import org.knowm.xchange.kucoin.dto.response.KucoinKline;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

import static org.knowm.xchange.kucoin.dto.KlineIntervalType.min5;

@RestController
public class StopOrderController {
    /** Logger. */
    private final Exchange exchange;
    private final MockTrader trader;


    public StopOrderController(Exchange exchange, MockTrader trader) {
        this.exchange = exchange;
        this.trader = trader;
    }

/*
    GET http://localhost:8080/purchase?baseSymbol=SOL&counterSymbol=USDT&startDate=2023-11-01T00:00:00&endDate=2023-12-01T00:00:00&walletUSDT=1800&stopLoss=95
*/
    @GetMapping(path = "stop-order")
    public List<String> checkPredict(
            @RequestParam String baseSymbol,
            @RequestParam String counterSymbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam BigDecimal walletUSDT) throws IOException {

        trader.walletUSDT = walletUSDT;

        List<KucoinKline> klines = ((KucoinMarketDataService) exchange.getMarketDataService())
                .getKucoinKlines(
                        new CurrencyPair(baseSymbol, counterSymbol),
                        startDate.minusDays(1).toEpochSecond(ZoneOffset.UTC),
                        startDate.toEpochSecond(ZoneOffset.UTC),
                        min5);
        Collections.reverse(klines);
        klines.forEach(trader::loadBarSeries);


        for (int day = 0; day < ChronoUnit.DAYS.between(startDate, endDate); day++) {

            klines = ((KucoinMarketDataService) exchange.getMarketDataService())
                    .getKucoinKlines(
                            new CurrencyPair(baseSymbol, counterSymbol),
                            startDate.plusDays(day).toEpochSecond(ZoneOffset.UTC),
                            startDate.plusDays(day + 1L).toEpochSecond(ZoneOffset.UTC),
                            min5);
            Collections.reverse(klines);
            klines.forEach(trader::loadBarSeries);

            for (KucoinKline kline : klines) {
                trader.next(kline);
            }

            trader.listResult.add("Day " + day);
        }

        return trader.listResult;
    }
}


