package dev.prayog.posttrade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Post-trade settings from {@code application.yaml} and environment variables.
 *
 * @param topic the exchange's event topic
 * @param charges the charge model (rates in parts per million of trade value)
 */
@ConfigurationProperties("prayog.post-trade")
public record PostTradeProperties(String topic, ChargeRates charges) {

    public record ChargeRates(long brokeragePpm, long brokerageCapPaise, long feesPpm) {}
}
