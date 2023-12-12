package com.trading.bot.configuration;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.*;

public class MovingMomentumStrategy {

    /**
     * @param series a time series
     * @return a moving momentum strategy
     */
    public static Strategy buildStrategy(BarSeries series) {
        if (series == null) {
            throw new IllegalArgumentException("Series cannot be null");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator smaIndicator = new SMAIndicator(closePrice, 5);

        // The bias is bullish when the shorter-moving average moves above the longer moving average.
        // The bias is bearish when the shorter-moving average moves below the longer moving average.
        EMAIndicator shortEma = new EMAIndicator(smaIndicator, 50);
        EMAIndicator longEma = new EMAIndicator(smaIndicator, 200);

        MACDIndicator macd = new MACDIndicator(smaIndicator, 12, 26);
        EMAIndicator signal = new EMAIndicator(macd, 9);

        // Entry rule
        Rule entryRule = new OverIndicatorRule(shortEma, longEma) // Trend
                .and(new CrossedDownIndicatorRule(signal, macd)); // Signal 1

        // Exit rule
        Rule exitRule = new CrossedUpIndicatorRule(signal, macd); // Signal 1

        return new BaseStrategy(entryRule, exitRule);
    }
}
