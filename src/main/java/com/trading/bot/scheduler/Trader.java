package com.trading.bot.scheduler;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.kucoin.dto.request.OrderCreateApiRequest;
import org.knowm.xchange.kucoin.dto.response.KucoinKline;
import org.knowm.xchange.service.trade.TradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.trading.bot.util.TradeUtil.*;
import static org.knowm.xchange.dto.Order.OrderType.*;

@Service
public class Trader {
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    private final Exchange exchange;
    private boolean purchased;
    private BigDecimal firstPrice;
    private final BarSeries barSeries;
    private final MACDIndicator macdLine;
    private final EMAIndicator signalLine;
    private final EMAIndicator emaIndicator50;
    private final EMAIndicator emaIndicator200;
    private float macdHistogramValue;

    @Value("${trader.buylimit}")
    public float tradeLimit;


    // Only for test !!!!!!!!!!!!!
    private BigDecimal curAccount = BigDecimal.valueOf(1000.0F);

    public Trader(Exchange exchange) {
        this.exchange = exchange;
        barSeries = new BaseBarSeries();
        macdLine = new MACDIndicator(new ClosePriceIndicator(barSeries));
        signalLine = new EMAIndicator(macdLine,9);
        emaIndicator50 = new EMAIndicator(new ClosePriceIndicator(barSeries),50);
        emaIndicator200 = new EMAIndicator(new ClosePriceIndicator(barSeries),200);
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

    @Scheduled(cron = "30 */5 * * * *")
    public void sell() throws IOException {
        final long startDate = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30).toEpochSecond(ZoneOffset.UTC);
        List<KucoinKline> kucoinKlines = getKucoinKlines(exchange, startDate, 0L);
        KucoinKline lastKline = kucoinKlines.get(1);
        loadBarSeries(barSeries, lastKline);
        float emaIndicator50Value = emaIndicator50.getValue(barSeries.getEndIndex()).floatValue();
        float emaIndicator200Value = emaIndicator200.getValue(barSeries.getEndIndex()).floatValue();

        if (!purchased && emaIndicator50Value > emaIndicator200Value) {
            TradeService tradeService = exchange.getTradeService();
            MarketOrder marketOrder = new MarketOrder(BID, BigDecimal.valueOf(1000), CurrencyPair.BTC_USDT);
            String response = tradeService.placeMarketOrder(marketOrder);

            firstPrice = lastKline.getClose();
            logger.info("BUY {} Price {} Response {}", curAccount, lastKline.getClose(), response);
            purchased = true;
            return;
        }

        if (purchased &&  emaIndicator200Value > emaIndicator50Value) {
            TradeService tradeService = exchange.getTradeService();
            MarketOrder marketOrder = new MarketOrder(ASK, BigDecimal.valueOf(1000), CurrencyPair.BTC_USDT);
            String response = tradeService.placeMarketOrder(marketOrder);

            curAccount = curAccount.multiply(lastKline.getClose()).divide(firstPrice, 2, RoundingMode.HALF_UP);
            logger.info("SELL {} firstPrice {} newPrice {} Response {}", curAccount, firstPrice, lastKline.getClose(), response);
            purchased = false;
        }
    }
}
