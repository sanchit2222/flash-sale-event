package com.cred.freestyle.flashsale.config;

import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

import java.time.Duration;
import java.util.Map;

/**
 * CloudWatch metrics configuration.
 * Publishes application metrics to AWS CloudWatch for observability.
 *
 * @author Flash Sale Team
 */
@Configuration
public class CloudWatchConfig {

    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    @Value("${cloud.aws.cloudwatch.namespace:FlashSale}")
    private String namespace;

    @Value("${cloud.aws.cloudwatch.batch-size:20}")
    private Integer batchSize;

    @Value("${cloud.aws.cloudwatch.step:PT1M}")
    private String step; // Publish interval (ISO-8601 duration)

    /**
     * Configure CloudWatch async client for publishing metrics.
     *
     * @return CloudWatchAsyncClient
     */
    @Bean
    public CloudWatchAsyncClient cloudWatchAsyncClient() {
        return CloudWatchAsyncClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * Configure CloudWatch meter registry.
     * This is the main interface for recording metrics.
     *
     * @param cloudWatchAsyncClient CloudWatch client
     * @return MeterRegistry
     */
    @Bean
    public MeterRegistry meterRegistry(CloudWatchAsyncClient cloudWatchAsyncClient) {
        io.micrometer.cloudwatch2.CloudWatchConfig cloudWatchConfig = new io.micrometer.cloudwatch2.CloudWatchConfig() {
            private final Map<String, String> configuration = Map.of(
                    "cloudwatch.namespace", namespace,
                    "cloudwatch.batchSize", String.valueOf(batchSize),
                    "cloudwatch.step", step
            );

            @Override
            public String get(String key) {
                return configuration.get(key);
            }

            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public int batchSize() {
                return batchSize;
            }

            @Override
            public Duration step() {
                return Duration.parse(step);
            }
        };

        return new CloudWatchMeterRegistry(
                cloudWatchConfig,
                Clock.SYSTEM,
                cloudWatchAsyncClient
        );
    }
}
