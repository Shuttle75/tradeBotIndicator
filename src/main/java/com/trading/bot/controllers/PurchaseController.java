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

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

/*
    GET http://localhost:8080/purchase?baseSymbol=SOL&counterSymbol=USDT&startDate=2023-11-01T00:00:00&endDate=2023-12-01T00:00:00&walletUSDT=1800&stopLoss=95
*/
    @GetMapping(path = "purchase")
    public List<String> checkPredict(
            @RequestParam String baseSymbol,
            @RequestParam String counterSymbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam BigDecimal walletUSDT,
            @RequestParam BigDecimal stopLoss) throws IOException {
        final BarSeries barSeries = new BaseBarSeries();
        final Strategy movingMomentumStrategy = MovingMomentumStrategy.buildStrategy(barSeries);

        long purchaseDate = 0;
        BigDecimal walletUSDTBefore = BigDecimal.valueOf(0);
        BigDecimal exitPrice = BigDecimal.valueOf(0);
        BigDecimal walletBase = BigDecimal.valueOf(0);
        List<String> listResult = new ArrayList<>();
        List<KucoinKline> kucoinKlines = ((KucoinMarketDataService) exchange.getMarketDataService())
                .getKucoinKlines(
                        new CurrencyPair(baseSymbol, counterSymbol),
                        startDate.minusDays(1).toEpochSecond(ZoneOffset.UTC),
                        startDate.toEpochSecond(ZoneOffset.UTC),
                        min5);
        Collections.reverse(kucoinKlines);
        kucoinKlines.forEach(kucoinKline -> loadBarSeries(barSeries, kucoinKline));

        TradingRecord tradingRecord = new BaseTradingRecord();

        for (int day = 0; day < ChronoUnit.DAYS.between(startDate, endDate); day++) {

            kucoinKlines = ((KucoinMarketDataService) exchange.getMarketDataService())
                    .getKucoinKlines(
                            new CurrencyPair(baseSymbol, counterSymbol),
                            startDate.plusDays(day).toEpochSecond(ZoneOffset.UTC),
                            startDate.plusDays(day + 1L).toEpochSecond(ZoneOffset.UTC),
                            min5);
            Collections.reverse(kucoinKlines);
            kucoinKlines.forEach(kucoinKline -> loadBarSeries(barSeries, kucoinKline));


            for (int i = 0; i < kucoinKlines.size(); i++) {
                final int index = 288 + 288 * day + i;
                final BigDecimal closePrice = kucoinKlines.get(i).getClose();

                if (tradingRecord.isClosed() && movingMomentumStrategy.shouldEnter(index, tradingRecord)) {

                    purchaseDate = kucoinKlines.get(i).getTime();
                    walletUSDTBefore = walletUSDT;
                    walletBase = walletUSDT.divide(closePrice, 0, RoundingMode.DOWN);
                    walletUSDT = walletUSDT.subtract(walletBase.multiply(closePrice));

                    tradingRecord.enter(index, DecimalNum.valueOf(closePrice), DecimalNum.valueOf(walletBase));
                }

                if (!tradingRecord.isClosed()
                        && DecimalNum.valueOf(closePrice)
                            .dividedBy(tradingRecord.getCurrentPosition().getEntry().getPricePerAsset())
                            .isLessThan(DecimalNum.valueOf(stopLoss.divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP)))
                        && (walletBase.compareTo(BigDecimal.valueOf(0)) > 0)) {

                        walletUSDT = walletUSDT.add(walletBase.multiply(closePrice));
                        walletBase = BigDecimal.valueOf(0);
                        exitPrice = closePrice;
                }

                if (!tradingRecord.isClosed() && movingMomentumStrategy.shouldExit(index, tradingRecord)) {

                    if (walletBase.compareTo(BigDecimal.valueOf(0)) > 0) {
                        tradingRecord.exit(index, DecimalNum.valueOf(closePrice), tradingRecord.getCurrentPosition().getEntry().getAmount());
                        walletUSDT = walletUSDT.add(walletBase.multiply(closePrice));
                        walletBase = BigDecimal.valueOf(0);
                        exitPrice = closePrice;
                    } else {
                        tradingRecord.exit(index, DecimalNum.valueOf(exitPrice), tradingRecord.getCurrentPosition().getEntry().getAmount());
                    }

                    listResult.add(ZonedDateTime.ofInstant(Instant.ofEpochSecond(purchaseDate), ZoneOffset.UTC) + " " +
                                   ZonedDateTime.ofInstant(Instant.ofEpochSecond(kucoinKlines.get(i).getTime()), ZoneOffset.UTC) + "   " +
                                   new DecimalFormat("#0.000").format(tradingRecord.getLastPosition().getEntry().getPricePerAsset().doubleValue()) + " " +
                                   new DecimalFormat("#0.000").format(tradingRecord.getLastPosition().getExit().getPricePerAsset().doubleValue()) + "   " +
                                   new DecimalFormat("#0.00").format(walletUSDTBefore.doubleValue()) + " " +
                                   new DecimalFormat("#0.00").format(walletUSDT.doubleValue()) + " " +
                                   new DecimalFormat("#0.00").format(tradingRecord.getLastPosition().getProfit().doubleValue()));
                }
            }
            listResult.add("Day " + day);
        }

        return listResult;
    }
}


