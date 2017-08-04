package com.google.pubsub.clients.producer;


import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Assert;
import org.junit.rules.ExpectedException;

import java.util.Properties;

import com.google.common.collect.ImmutableMap;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Created by gewillovic on 8/1/17.
 */

public class ConfigTest {

  @Rule
  public final ExpectedException exception = ExpectedException.none();

  @Test
  public void testSuccessAllConfigsProvided() {
    Properties props = new Properties();
    props.putAll(new ImmutableMap.Builder<>()
        .put("topic", "unit-test-topic")
        .put("project", "unit-test-project")
        .put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        .put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        .build()
    );

    ProducerConfig testConfig = new ProducerConfig(props);

    Assert.assertEquals("Topic config equals unit-test-topic.",
        "unit-test-topic", testConfig.getString(ProducerConfig.TOPIC_CONFIG));
    Assert.assertEquals("Project config equals unit-test-project.",
        "unit-test-project", testConfig.getString(ProducerConfig.PROJECT_CONFIG));

    Assert.assertNotNull("Key serializer must not be null.",
        testConfig.getConfiguredInstance(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, Serializer.class));
    Assert.assertEquals("Key serializer config must equal StringSerializer.",
        StringSerializer.class, testConfig.getClass(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));

    Assert.assertNotNull("Value serializer must not be null.",
        testConfig.getConfiguredInstance(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, Serializer.class));
    Assert.assertEquals("Value serializer config must equal StringSerializer.",
        StringSerializer.class, testConfig.getClass(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
  }

  @Test
  public void testNoSerializerProvided() {
    Properties props = new Properties();
    props.putAll(new ImmutableMap.Builder<>()
        .put("topic", "unit-test-topic")
        .put("project", "unit-test-project")
        .build()
    );

    exception.expect(ConfigException.class);
    new ProducerConfig(props);
  }

  @Test
  public void testNoTopicProvided() {
    Properties props = new Properties();
    props.putAll(new ImmutableMap.Builder<>()
        .put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        .put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        .build()
    );

    exception.expect(ConfigException.class);
    new ProducerConfig(props);
  }
}