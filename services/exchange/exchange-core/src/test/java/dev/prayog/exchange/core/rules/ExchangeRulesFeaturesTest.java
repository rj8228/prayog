package dev.prayog.exchange.core.rules;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * S20: the exchange's matching rules as Given/When/Then scenarios ({@code src/test/resources/features}), run against
 * the real matching engine on every build. They read as the rulebook; the step definitions are in {@link RuleSteps}.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.prayog.exchange.core.rules")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "summary, html:target/cucumber-rules.html")
class ExchangeRulesFeaturesTest {}
