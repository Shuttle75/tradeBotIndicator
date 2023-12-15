package com.trading.bot.controllers;

import com.trading.bot.configuration.MovingMomentumStrategy;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.kucoin.KucoinMarketDataService;
import org.knowm.xchange.kucoin.dto.response.KucoinKline;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.ta4j.core.*;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import java.io.IOException;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.trading.bot.scheduler.Trader.loadBarSeries;
import static org.knowm.xchange.kucoin.dto.KlineIntervalType.min5;

@RestController
public class PurchaseController {
    /** Logger. */
    private final Exchange exchange;


    public PurchaseController(Exchange exchange) {
        this.exchange = exchange;
    }

    @GetMapping(path = "purchase")
    public List<String> checkPredict(
            @RequestParam String base,
            @RequestParam String counter,
            @RequestParam(name = "start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(name = "end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) throws IOException {
        final BarSeries barSeries = new BaseBarSeries();
        final Strategy movingMomentumStrategy = MovingMomentumStrategy.buildStrategy(barSeries);

        Num walletTrade = DecimalNum.valueOf(1800);
        long purchaseDate = 0;
        List<String> listResult = new ArrayList<>();
        List<KucoinKline> kucoinKlines = ((KucoinMarketDataService) exchange.getMarketDataService())
                .getKucoinKlines(
                        new CurrencyPair(base, counter),
                        startDate.minusDays(1).toEpochSecond(ZoneOffset.UTC),
                        startDate.toEpochSecond(ZoneOffset.UTC),
                        min5);
        Collections.reverse(kucoinKlines);
        kucoinKlines.forEach(kucoinKline -> loadBarSeries(barSeries, kucoinKline));

        TradingRecord tradingRecord = new BaseTradingRecord();

        for (int day = 0; day < ChronoUnit.DAYS.between(startDate, endDate); day++) {

            kucoinKlines = ((KucoinMarketDataService) exchange.getMarketDataService())
                    .getKucoinKlines(
                            new CurrencyPair(base, counter),
                            startDate.plusDays(day).toEpochSecond(ZoneOffset.UTC),
                            startDate.plusDays(day + 1L).toEpochSecond(ZoneOffset.UTC),
                            min5);
            Collections.reverse(kucoinKlines);
            kucoinKlines.forEach(kucoinKline -> loadBarSeries(barSeries, kucoinKline));


            for (int i = 0; i < kucoinKlines.size(); i++) {
                int index = 288 + 288 * day + i;
                Num startWalletTrade = walletTrade;

                if (tradingRecord.isClosed()
                        && movingMomentumStrategy.shouldEnter(index, tradingRecord)) {
                    purchaseDate = kucoinKlines.get(i).getTime();
                    tradingRecord.enter(index, DecimalNum.valueOf(kucoinKlines.get(i).getClose()), DecimalNum.valueOf(40));
                }

                if (!tradingRecord.isClosed()
                        && movingMomentumStrategy.shouldExit(index, tradingRecord)) {
                    tradingRecord.exit(index, DecimalNum.valueOf(kucoinKlines.get(i).getClose()), DecimalNum.valueOf(40));
                    walletTrade = walletTrade.plus(tradingRecord.getLastPosition().getProfit());

                    listResult.add(ZonedDateTime.ofInstant(Instant.ofEpochSecond(purchaseDate), ZoneOffset.UTC) + " " +
                                   ZonedDateTime.ofInstant(Instant.ofEpochSecond(kucoinKlines.get(i).getTime()), ZoneOffset.UTC) + "   " +
                                   new DecimalFormat("#0.000").format(tradingRecord.getLastPosition().getEntry().getPricePerAsset().doubleValue()) + " " +
                                   new DecimalFormat("#0.000").format(tradingRecord.getLastPosition().getExit().getPricePerAsset().doubleValue()) + "   " +
                                   new DecimalFormat("#0.00").format(startWalletTrade.doubleValue()) + " " +
                                   new DecimalFormat("#0.00").format(walletTrade.doubleValue()) + " " +
                                   new DecimalFormat("#0.00").format(tradingRecord.getLastPosition().getProfit().doubleValue()));
                }
            }
            listResult.add("Day " + day);
        }

        return listResult;
    }
}


