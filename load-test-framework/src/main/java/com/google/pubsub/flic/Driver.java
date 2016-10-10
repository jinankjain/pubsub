// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////
package com.google.pubsub.flic;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Timestamp;
import com.google.pubsub.flic.common.LatencyDistribution;
import com.google.pubsub.flic.controllers.Client;
import com.google.pubsub.flic.controllers.Client.ClientType;
import com.google.pubsub.flic.controllers.ClientParams;
import com.google.pubsub.flic.controllers.Controller;
import com.google.pubsub.flic.controllers.GCEController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.stream.LongStream;

/**
 * Drives the execution of the framework through command line arguments.
 */
class Driver {
  private final static Logger log = LoggerFactory.getLogger(Driver.class);
  @Parameter(
      names = {"--help"},
      help = true
  )
  private boolean help = false;
  @Parameter(
      names = {"--cps_publisher_count"},
      description = "Number of CPS publishers to start."
  )
  private int cpsPublisherCount = 1;
  @Parameter(
      names = {"--cps_subscriber_count"},
      description = "Number of CPS subscribers to start."
  )
  private int cpsSubscriberCount = 1;
  @Parameter(
      names = {"--kafka_publisher_count"},
      description = "Number of Kafka publishers to start."
  )
  private int kafkaPublisherCount = 0;
  @Parameter(
      names = {"--kafka_subscriber_count"},
      description = "Number of Kafka subscribers to start."
  )
  private int kafkaSubscriberCount = 0;
  @Parameter(
      names = {"--message_size", "-m"},
      description = "Message size in bytes (only when publishing messages).",
      validateWith = GreaterThanZeroValidator.class
  )
  private int messageSize = 100000;
  @Parameter(
      names = {"--loadtest_seconds"},
      description = "Duration of the load test, in seconds.",
      validateWith = GreaterThanZeroValidator.class
  )
  private int loadtestLengthSeconds = 60;
  @Parameter(
      names = {"--project"},
      required = true,
      description = "Google Cloud Platform project name."
  )
  private String project = "";
  @Parameter(
      names = {"--batch_size", "-b"},
      description = "Number of messages to batch per publish request.",
      validateWith = GreaterThanZeroValidator.class
  )
  private int batchSize = 10;
  @Parameter(
      names = {"--subscription_fanout"},
      description = "Number of subscriptions to create for each topic. Must be at least 1.",
      validateWith = GreaterThanZeroValidator.class
  )
  private int subscriptionFanout = 1;
  @Parameter(
      names = {"--broker"},
      description = "The network address of the Kafka broker."
  )
  private String broker;
  @Parameter(
      names = {"--request_rate"},
      description = "The rate at which each client will make requests."
  )
  private int requestRate = 10;
  @Parameter(
      names = {"--max_outstanding_requests"},
      description = "The maximum number of outstanding requests each client will allow."
  )
  private int maxOutstandingRequests = 20;
  @Parameter(
      names = {"--burn_in_duration_seconds"},
      description = "The duration, in seconds, to run without recording statistics in order to allow tuning."
  )
  private int burnInDurationSeconds = 20;
  @Parameter(
      names = {"--number_of_messages"},
      description = "The total number of messages to publish in the test. Enabling this will override " +
          "--loadtest_length_seconds. Enabling this flag will also enable the check for message loss. If set less " +
          "than 1, this flag is ignored."
  )
  private int numberOfMessages = 0;

  public static void main(String[] args) {
    // Turns off all java.util.logging.
    LogManager.getLogManager().reset();
    Driver driver = new Driver();
    JCommander jCommander = new JCommander(driver, args);
    if (driver.help) {
      jCommander.usage();
      return;
    }
    driver.run();
  }

  private void run() {
    try {
      //Map<String, Map<ClientParams, Integer>> clientTypes = ImmutableMap.of(
      //    "us-central1-a", new HashMap<>());
      Preconditions.checkArgument(
          cpsPublisherCount > 0 ||
              cpsSubscriberCount > 0 ||
              kafkaPublisherCount > 0 ||
              kafkaSubscriberCount > 0
      );
      Preconditions.checkArgument(
          broker != null || (kafkaPublisherCount == 0 && kafkaSubscriberCount == 0));
      Map<ClientParams, Integer> clientParamsMap = new HashMap<>();
      clientParamsMap.putAll(ImmutableMap.of(
          new ClientParams(ClientType.CPS_GCLOUD_PUBLISHER, null), cpsPublisherCount,
          new ClientParams(ClientType.KAFKA_PUBLISHER, null), kafkaPublisherCount,
          new ClientParams(ClientType.KAFKA_SUBSCRIBER, null), kafkaSubscriberCount
      ));
      for (int i = 0; i < subscriptionFanout; ++i) {
        clientParamsMap.put(new ClientParams(ClientType.CPS_GCLOUD_SUBSCRIBER, "gcloud-subscription" + i),
            cpsSubscriberCount / subscriptionFanout);
      }
      Client.messageSize = messageSize;
      Client.requestRate = 1;
      Client.startTime = Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000 + 90).build();
      Client.loadtestLengthSeconds = loadtestLengthSeconds;
      Client.batchSize = batchSize;
      Client.broker = broker;
      Client.requestRate = requestRate;
      Client.maxOutstandingRequests = maxOutstandingRequests;
      Client.burnInTimeMillis = (Client.startTime.getSeconds() + burnInDurationSeconds) * 1000;
      Client.numberOfMessages = numberOfMessages;
      GCEController gceController = GCEController.newGCEController(
          project, ImmutableMap.of("us-central1-a", clientParamsMap), Executors.newScheduledThreadPool(500));
      gceController.startClients();

      // Start a thread to poll and output results.
      ScheduledExecutorService pollingExecutor = Executors.newSingleThreadScheduledExecutor();
      pollingExecutor.scheduleWithFixedDelay(() -> {
        synchronized (pollingExecutor) {
          Map<ClientType, Controller.Result> results = gceController.getResults(false);
          log.info("===============================================");
          results.forEach((type, result) -> {
            log.info("Results for " + type + ":");
            log.info("50%: " + LatencyDistribution.getNthPercentile(result.bucketValues, 0.5));
            log.info("99%: " + LatencyDistribution.getNthPercentile(result.bucketValues, 0.99));
            log.info("99.9%: " + LatencyDistribution.getNthPercentile(result.bucketValues, 0.999));
          });
          log.info("===============================================");
        }
      }, 5, 5, TimeUnit.SECONDS);
      // Wait for the load test to finish.
      Map<ClientType, Controller.Result> results = gceController.getResults(true);
      synchronized (pollingExecutor) {
        pollingExecutor.shutdownNow();
      }
      results.forEach((type, result) -> {
        log.info("Results for " + type + ":");
        log.info("50%: " + LatencyDistribution.getNthPercentile(result.bucketValues, 0.5));
        log.info("99%: " + LatencyDistribution.getNthPercentile(result.bucketValues, 0.99));
        log.info("99.9%: " + LatencyDistribution.getNthPercentile(result.bucketValues, 0.999));
        // CPS Publishers report latency per batch message.
        log.info("Average throughput: " +
            new DecimalFormat("#.##").format(
                (double) LongStream.of(
                    result.bucketValues).sum() / (result.endTimeMillis / 1000.0 - Client.startTime.getSeconds())
                    * messageSize / 1000000.0 * (type == ClientType.CPS_GCLOUD_PUBLISHER ? batchSize : 1)) +
            " MB/s");
      });
      gceController.shutdown(null);
      System.exit(0);
    } catch (Throwable t) {
      log.error("An error occurred...", t);
      System.exit(1);
    }
  }

  /**
   * A validator that makes sure the parameter is an integer that is greater than 0.
   */
  private static class GreaterThanZeroValidator implements IParameterValidator {
    @Override
    public void validate(String name, String value) throws ParameterException {
      try {
        int n = Integer.parseInt(value);
        if (n > 0) return;
        throw new NumberFormatException();
      } catch (NumberFormatException e) {
        throw new ParameterException(
            "Parameter " + name + " should be an int greater than 0 (found " + value + ")");
      }
    }
  }
}
