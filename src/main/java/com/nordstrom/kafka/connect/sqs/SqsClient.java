/*
 * Copyright 2019 Nordstrom, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.nordstrom.kafka.connect.sqs;

import java.util.List;
import java.util.Map;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import org.apache.kafka.common.Configurable;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.DeleteMessageResult;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageResult;

public class SqsClient {
  private final Logger log = LoggerFactory.getLogger(this.getClass());

  private final String AWS_FIFO_SUFFIX = ".fifo";

  private final AmazonSQS client;

  public SqsClient(Map<String, ?> configs) {
    log.warn(".ctor:configs={}", configs);
    AWSCredentialsProvider provider = null;
    try {
      provider = getCredentialsProvider(configs);
    } catch ( Exception e ) {
      log.error("Problem initializing provider", e);
    }
    final AmazonSQSClientBuilder builder = AmazonSQSClientBuilder.standard();
    builder.setCredentials(provider);

    final String region = System.getenv("AWS_REGION");
    final String localstackEndpoint = System.getenv("LOCALSTACK_ENDPOINT");

    // for local testing without an AWS account
    if (Facility.isNotNullNorEmpty(localstackEndpoint)) {
      log.info("Applying endpoint configuration {} -> {}", localstackEndpoint, region);
      builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(localstackEndpoint, region));
    }

    client = builder.build();
  }

  /**
   * Delete a message from the SQS queue.
   *
   * @param url           SQS queue url.
   * @param receiptHandle Message receipt handle of message to delete.
   */
  public void delete(final String url, final String receiptHandle) {
    Guard.verifyValidUrl(url);
    Guard.verifyNotNullOrEmpty(receiptHandle, "receiptHandle");

    final DeleteMessageRequest request = new DeleteMessageRequest(url, receiptHandle);
    final DeleteMessageResult result = client.deleteMessage(request);

    log.debug(".delete:receipt-handle={}, rc={}", receiptHandle, result.getSdkHttpMetadata().getHttpStatusCode());
  }

  /**
   * Receive messages from the SQS queue.
   *
   * @param url             SQS queue url.
   * @param maxMessages     Maximum number of messages to receive for this call.
   * @param waitTimeSeconds Time to wait, in seconds, for messages to arrive.
   * @return Collection of messages received.
   */
  public List<Message> receive(final String url, final int maxMessages, final int waitTimeSeconds) {
    log.debug(".receive:queue={}, max={}, wait={}", url, maxMessages, waitTimeSeconds);

    Guard.verifyValidUrl(url);
    Guard.verifyNonNegative(waitTimeSeconds, "sqs.wait.time.seconds");
    Guard.verifyInRange(maxMessages, 0, 10, "sqs.max.messages");
    if (!isValidState()) {
      throw new IllegalStateException("AmazonSQS client is not initialized");
    }

    //
    // Receive messages from queue
    //
    final ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(url)
        .withMaxNumberOfMessages(maxMessages).withWaitTimeSeconds(waitTimeSeconds).withAttributeNames("");
    final ReceiveMessageResult result = client.receiveMessage(receiveMessageRequest);
    final List<Message> messages = result.getMessages();

    log.debug(".receive:{} messages, url={}, rc={}", messages.size(), url,
        result.getSdkHttpMetadata().getHttpStatusCode());

    return messages;
  }

  /**
   * Send a message to an SQS queue.
   *
   * @param url       SQS queue url.
   * @param body      The message to send.
   * @param groupId   Optional group identifier (fifo queues only).
   * @param messageId Optional message identifier (fifo queues only).
   * @return
   */
  public String send(final String url, final String body, final String groupId, final String messageId) {
    log.debug(".send: queue={}, gid={}, mid={}", url, groupId, messageId);

    Guard.verifyValidUrl(url);
    // Guard.verifyNotNullOrEmpty( body, "message body" ) ;
    if (!isValidState()) {
      throw new IllegalStateException("AmazonSQS client is not initialized");
    }
    final boolean fifo = isFifo(url);

    final SendMessageRequest request = new SendMessageRequest(url, body);
    if (fifo) {
      Guard.verifyNotNullOrEmpty(groupId, "groupId");
      Guard.verifyNotNullOrEmpty(messageId, "messageId");
      request.setMessageGroupId(groupId);
      request.setMessageDeduplicationId(messageId);
    }

    final SendMessageResult result = client.sendMessage(request);

    log.debug(".send-message.OK: queue={}, result={}", url, result);

    return fifo ? result.getSequenceNumber() : result.getMessageId();
  }

  private boolean isFifo(final String url) {
    return url.endsWith(AWS_FIFO_SUFFIX);
  }

  /**
   * Test that we have properly initialized the AWS SQS client.
   *
   * @return true if client is in a valid state.
   */
  private boolean isValidState() {
    return Facility.isNotNull(client);
  }

  @SuppressWarnings("unchecked")
  public AWSCredentialsProvider getCredentialsProvider(Map<String, ?> configs) {
    log.warn(".get-credentials-provider:configs={}", configs);

    try {
      Object providerField = configs.get("class");
      String providerClass = SqsConnectorConfigKeys.CREDENTIALS_PROVIDER_CLASS_DEFAULT.getValue();
      if (null != providerField) {
        providerClass = providerField.toString();
      }
      log.warn(".get-credentials-provider:field={}, class={}", providerField, providerClass);
      AWSCredentialsProvider provider = ((Class<? extends AWSCredentialsProvider>)
          getClass(providerClass)).newInstance();

      if (provider instanceof Configurable) {
//        Map<String, Object> configs = originalsWithPrefix(CREDENTIALS_PROVIDER_CONFIG_PREFIX);
//        configs.remove(CREDENTIALS_PROVIDER_CLASS_CONFIG.substring(
//            CREDENTIALS_PROVIDER_CONFIG_PREFIX.length(),
//            CREDENTIALS_PROVIDER_CLASS_CONFIG.length()
//        ));
        ((Configurable) provider).configure(configs);
      }

      log.warn(".get-credentials-provider:provider={}", provider);
      return provider;
    } catch (IllegalAccessException | InstantiationException e) {
      throw new ConnectException(
          "Invalid class for: " + SqsConnectorConfigKeys.CREDENTIALS_PROVIDER_CLASS_CONFIG,
          e
      );
    }
  }

  public Class<?> getClass(String className) {
    log.warn(".get-class:class={}",className);
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException e) {
      log.error("Provider class not found: {}", e);
    }
    return null;
  }

}
