/*
 * Copyright 2018 Wultra s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.getlime.push.service;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.Message;
import com.turo.pushy.apns.ApnsClient;
import com.turo.pushy.apns.ApnsClientBuilder;
import com.turo.pushy.apns.DeliveryPriority;
import com.turo.pushy.apns.PushNotificationResponse;
import com.turo.pushy.apns.auth.ApnsSigningKey;
import com.turo.pushy.apns.proxy.HttpProxyHandlerFactory;
import com.turo.pushy.apns.util.ApnsPayloadBuilder;
import com.turo.pushy.apns.util.SimpleApnsPushNotification;
import com.turo.pushy.apns.util.TokenUtil;
import com.turo.pushy.apns.util.concurrent.PushNotificationFuture;
import com.turo.pushy.apns.util.concurrent.PushNotificationResponseListener;
import io.getlime.push.configuration.PushServiceConfiguration;
import io.getlime.push.errorhandling.exceptions.FcmMissingTokenException;
import io.getlime.push.errorhandling.exceptions.PushServerException;
import io.getlime.push.model.entity.PushMessageAttributes;
import io.getlime.push.model.entity.PushMessageBody;
import io.getlime.push.service.fcm.FcmClient;
import io.getlime.push.service.fcm.FcmModelConverter;
import io.getlime.push.service.fcm.model.FcmErrorResponse;
import io.getlime.push.service.fcm.model.FcmSuccessResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


@Service
public class PushSendingWorker {

    private static final Logger logger = LoggerFactory.getLogger(PushSendingWorker.class);

    // FCM data only notification keys
    private static final String FCM_NOTIFICATION_KEY            = "_notification";

    // Expected response String from FCM
    private static final String FCM_RESPONSE_VALID_REGEXP       = "projects/.+/messages/.+";

    // APNS bad device token String
    private static final String APNS_BAD_DEVICE_TOKEN           = "BadDeviceToken";

    // APNS device token not for topic String
    private static final String APNS_DEVICE_TOKEN_NOT_FOR_TOPIC = "DeviceTokenNotForTopic";

    // APNS topic disallowed String
    private static final String APNS_TOPIC_DISALLOWED           = "TopicDisallowed";

    private final PushServiceConfiguration pushServiceConfiguration;
    private final FcmModelConverter fcmConverter;

    @Autowired
    public PushSendingWorker(PushServiceConfiguration pushServiceConfiguration, FcmModelConverter fcmConverter) {
        this.pushServiceConfiguration = pushServiceConfiguration;
        this.fcmConverter = fcmConverter;
    }

    // Android related methods

    /**
     * Prepares an FCM service client with a provided server key.
     *
     * @param projectId FCM project ID.
     * @param privateKey FCM private key.
     * @return A new instance of FCM client.
     */
    FcmClient prepareFcmClient(String projectId, byte[] privateKey) throws PushServerException {
        FcmClient fcmClient = new FcmClient(projectId, privateKey, pushServiceConfiguration, fcmConverter);
        if (pushServiceConfiguration.isFcmProxyEnabled()) {
            String proxyHost = pushServiceConfiguration.getFcmProxyUrl();
            int proxyPort = pushServiceConfiguration.getFcmProxyPort();
            String proxyUsername = pushServiceConfiguration.getFcmProxyUsername();
            String proxyPassword = pushServiceConfiguration.getFcmProxyPassword();
            if (proxyUsername != null && proxyUsername.isEmpty()) {
                proxyUsername = null;
            }
            if (proxyPassword != null && proxyPassword.isEmpty()) {
                proxyPassword = null;
            }
            fcmClient.setProxySettings(proxyHost, proxyPort, proxyUsername, proxyPassword);
        }
        fcmClient.initializeWebClient();
        fcmClient.initializeGoogleCredential();
        String fcmUrl = pushServiceConfiguration.getFcmSendMessageUrl();
        if (fcmUrl.contains("projects/%s/")) {
            // Configure project ID in FCM URL in case the project ID parameter is expected in configured URL
            fcmClient.setFcmSendMessageUrl(String.format(fcmUrl, projectId));
        } else {
            // Set FCM url as is (e.g. for testing)
            fcmClient.setFcmSendMessageUrl(fcmUrl);
        }
        return fcmClient;
    }

    /**
     * Send message to Android platform.
     * @param fcmClient Instance of the FCM client used for sending the notifications.
     * @param pushMessageBody Push message contents.
     * @param attributes Push message attributes.
     * @param pushToken Push token used to deliver the message.
     * @param callback Callback that is called after the asynchronous executions is completed.
     */
    void sendMessageToAndroid(final FcmClient fcmClient, final PushMessageBody pushMessageBody, final PushMessageAttributes attributes, final String pushToken, final PushSendingCallback callback) {

        // Build Android message
        Message message = buildAndroidMessage(pushMessageBody, attributes, pushToken);

        // Callback when FCM request succeeds
        Consumer<FcmSuccessResponse> onSuccess = body -> {
            if (body.getName() != null && body.getName().matches(FCM_RESPONSE_VALID_REGEXP)) {
                logger.info("Notification sent, response: {}", body.getName());
                callback.didFinishSendingMessage(PushSendingCallback.Result.OK);
                return;
            }
            // This state should not happen, only in case when response from server is invalid
            logger.error("Invalid response received from FCM, notification sending failed");
            callback.didFinishSendingMessage(PushSendingCallback.Result.FAILED);
        };

        // Callback when FCM request fails
        Consumer<Throwable> onError = t -> {
            if (t instanceof WebClientResponseException) {
                String errorCode = fcmConverter.convertExceptionToErrorCode((WebClientResponseException) t);
                switch (errorCode) {
                    case FcmErrorResponse.REGISTRATION_TOKEN_NOT_REGISTERED:
                        logger.error("Push message rejected by FCM gateway, device registration will be removed. Error: {}", errorCode);
                        callback.didFinishSendingMessage(PushSendingCallback.Result.FAILED_DELETE);
                        return;

                    case FcmErrorResponse.SERVER_UNAVAILABLE:
                    case FcmErrorResponse.INTERNAL_ERROR:
                    case FcmErrorResponse.MESSAGE_RATE_EXCEEDED:
                        // TODO - implement throttling of messages, see:
                        // https://firebase.google.com/docs/cloud-messaging/admin/errors
                        logger.error("Push message rejected by FCM gateway, message status set to PENDING. Error: {}", errorCode);
                        callback.didFinishSendingMessage(PushSendingCallback.Result.PENDING);
                        return;

                    case FcmErrorResponse.MISMATCHED_CREDENTIAL:
                    case FcmErrorResponse.INVALID_APNS_CREDENTIALS:
                    case FcmErrorResponse.INVALID_ARGUMENT:
                    case FcmErrorResponse.UNKNOWN_ERROR:
                        logger.error("Push message rejected by FCM gateway, error: {}", errorCode);
                        callback.didFinishSendingMessage(PushSendingCallback.Result.FAILED);
                        return;

                    default:
                        logger.error("Unexpected error code received from FCM gateway: {}", errorCode);
                        callback.didFinishSendingMessage(PushSendingCallback.Result.FAILED);
                        return;
                }
            }

            // Unexepected errors
            logger.error("Unexpected error occurred while sending push message: {}", t.getMessage(), t);
            callback.didFinishSendingMessage(PushSendingCallback.Result.FAILED);
        };

        // Perform request to FCM asynchronously, either of the consumers is called in case of success or error
        try {
            fcmClient.exchange(message, false, onSuccess, onError);
        } catch (FcmMissingTokenException ex) {
            logger.error(ex.getMessage(), ex);
            callback.didFinishSendingMessage(PushSendingCallback.Result.FAILED);
        }
    }

    /**
     * Build Android Message object from Push message body.
     * @param pushMessageBody Push message body.
     * @param attributes Push message attributes.
     * @param pushToken Push token.
     * @return Android Message object.
     */
    private Message buildAndroidMessage(final PushMessageBody pushMessageBody, final PushMessageAttributes attributes, final String pushToken) {
        // convert data from Map<String, Object> to Map<String, String>
        Map<String, Object> extras = pushMessageBody.getExtras();
        Map<String, String> data = new LinkedHashMap<>();
        if (extras != null) {
            for (Map.Entry<String, Object> entry : extras.entrySet()) {
                data.put(entry.getKey(), entry.getValue().toString());
            }
        }

        AndroidConfig.Builder androidConfigBuilder = AndroidConfig.builder()
                .setCollapseKey(pushMessageBody.getCollapseKey());

        AndroidNotification notification = AndroidNotification.builder()
                .setTitle(pushMessageBody.getTitle())
                .setBody(pushMessageBody.getBody())
                .setIcon(pushMessageBody.getIcon())
                .setSound(pushMessageBody.getSound())
                .setTag(pushMessageBody.getCategory())
                .build();

        if (pushServiceConfiguration.isFcmDataNotificationOnly()) { // notification only through data map
            data.put(FCM_NOTIFICATION_KEY, fcmConverter.convertNotificationToString(notification));
        } else if (attributes == null || !attributes.getSilent()) { // if there are no attributes, assume the message is not silent
            androidConfigBuilder.setNotification(notification);
        }

        return Message.builder()
                .setToken(pushToken)
                .putAllData(data)
                .setAndroidConfig(androidConfigBuilder.build())
                .build();
    }

    // iOS related methods

    /**
     * Prepare and connect APNs client.
     *
     * @param teamId APNs team ID.
     * @param keyId APNs key ID.
     * @param apnsPrivateKey Bytes of the APNs private key (contents of the *.p8 file).
     * @return New instance of APNs client.
     * @throws PushServerException In case an error occurs (private key is invalid, unable to connect
     *   to APNs service due to SSL issue, ...).
     */
    ApnsClient prepareApnsClient(String teamId, String keyId, byte[] apnsPrivateKey) throws PushServerException {
        final ApnsClientBuilder apnsClientBuilder = new ApnsClientBuilder();
        apnsClientBuilder.setProxyHandlerFactory(apnsClientProxy());
        apnsClientBuilder.setConnectionTimeout(pushServiceConfiguration.getApnsConnectTimeout(), TimeUnit.MILLISECONDS);
        if (pushServiceConfiguration.isApnsUseDevelopment()) {
            apnsClientBuilder.setApnsServer(ApnsClientBuilder.DEVELOPMENT_APNS_HOST);
        } else {
            apnsClientBuilder.setApnsServer(ApnsClientBuilder.PRODUCTION_APNS_HOST);
        }
        try {
            ApnsSigningKey key = ApnsSigningKey.loadFromInputStream(new ByteArrayInputStream(apnsPrivateKey), teamId, keyId);
            apnsClientBuilder.setSigningKey(key);
        } catch (InvalidKeyException | NoSuchAlgorithmException | IOException e) {
            logger.error(e.getMessage(), e);
            throw new PushServerException("Invalid private key", e);
        }
        try {
            return apnsClientBuilder.build();
        } catch (SSLException e) {
            logger.error(e.getMessage(), e);
            throw new PushServerException("SSL problem", e);
        }
    }

    /**
     * Prepare proxy settings for APNs client.
     *
     * @return Proxy handler factory with correct configuration.
     */
    private HttpProxyHandlerFactory apnsClientProxy() {
        if (pushServiceConfiguration.isApnsProxyEnabled()) {
            String proxyUrl = pushServiceConfiguration.getApnsProxyUrl();
            int proxyPort = pushServiceConfiguration.getApnsProxyPort();
            String proxyUsername = pushServiceConfiguration.getApnsProxyUsername();
            String proxyPassword = pushServiceConfiguration.getApnsProxyPassword();
            if (proxyUsername != null && proxyUsername.isEmpty()) {
                proxyUsername = null;
            }
            if (proxyPassword != null && proxyPassword.isEmpty()) {
                proxyPassword = null;
            }
            return new HttpProxyHandlerFactory(new InetSocketAddress(proxyUrl, proxyPort), proxyUsername, proxyPassword);
        }
        return null;
    }

    /**
     * Send message to iOS platform.
     *
     * @param apnsClient APNs client used for sending the push message.
     * @param pushMessageBody Push message content.
     * @param attributes Push message attributes.
     * @param pushToken Push token.
     * @param iosTopic APNs topic, usually same as bundle ID.
     * @param callback Callback that is called after the asynchronous executions is completed.
     */
    void sendMessageToIos(final ApnsClient apnsClient, final PushMessageBody pushMessageBody, final PushMessageAttributes attributes, final String pushToken, final String iosTopic, final PushSendingCallback callback) {

        final String token = TokenUtil.sanitizeTokenString(pushToken);
        final String payload = buildApnsPayload(pushMessageBody, attributes == null ? false : attributes.getSilent()); // In case there are no attributes, the message is not silent
        Date validUntil = pushMessageBody.getValidUntil();
        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(token, iosTopic, payload, validUntil, DeliveryPriority.IMMEDIATE, pushMessageBody.getCollapseKey());
        final PushNotificationFuture<SimpleApnsPushNotification, PushNotificationResponse<SimpleApnsPushNotification>> sendNotificationFuture = apnsClient.sendNotification(pushNotification);

        sendNotificationFuture.addListener((PushNotificationResponseListener<SimpleApnsPushNotification>) future -> {
            if (future.isSuccess()) {
                final PushNotificationResponse<SimpleApnsPushNotification> pushNotificationResponse = future.getNow();
                if (pushNotificationResponse != null) {
                    if (!pushNotificationResponse.isAccepted()) {
                        logger.error("Notification rejected by the APNs gateway: {}", pushNotificationResponse.getRejectionReason());
                        if (pushNotificationResponse.getRejectionReason().equals(APNS_BAD_DEVICE_TOKEN)) {
                            logger.error("\t... due to bad device token value.");
                            callback.didFinishSendingMessage(PushSendingCallback.Result.FAILED_DELETE);
                        } else if (pushNotificationResponse.getRejectionReason().equals(APNS_DEVICE_TOKEN_NOT_FOR_TOPIC)) {
                            logger.error("\t... due to device token not for topic error.");
                            callback.didFinishSendingMessage(PushSendingCallback.Result.FAILED_DELETE);
                        } else if (pushNotificationResponse.getRejectionReason().equals(APNS_TOPIC_DISALLOWED)) {
                            logger.error("\t... due to topic disallowed error.");
                            callback.didFinishSendingMessage(PushSendingCallback.Result.FAILED_DELETE);
                        } else if (pushNotificationResponse.getTokenInvalidationTimestamp() != null) {
                            logger.error("\t... and the token is invalid as of " + pushNotificationResponse.getTokenInvalidationTimestamp());
                            callback.didFinishSendingMessage(PushSendingCallback.Result.FAILED_DELETE);
                        } else {
                            callback.didFinishSendingMessage(PushSendingCallback.Result.FAILED);
                        }
                    } else {
                        logger.info("Notification sent, APNS ID: {}", pushNotificationResponse.getApnsId());
                        callback.didFinishSendingMessage(PushSendingCallback.Result.OK);
                    }
                } else {
                    logger.error("Notification rejected by the APNs gateway: unknown error, will retry");
                    callback.didFinishSendingMessage(PushSendingCallback.Result.PENDING);
                }
            } else {
                logger.error("Push Message Sending Failed", future.cause());
                callback.didFinishSendingMessage(PushSendingCallback.Result.FAILED);
            }
        });
    }

    /**
     * Method to build APNs message payload.
     *
     * @param push     Push message object with APNs data.
     * @param isSilent Indicates if the message is silent or not.
     * @return String with APNs JSON payload.
     */
    private String buildApnsPayload(PushMessageBody push, boolean isSilent) {
        final ApnsPayloadBuilder payloadBuilder = new ApnsPayloadBuilder();
        payloadBuilder.setAlertTitle(push.getTitle());
        payloadBuilder.setAlertBody(push.getBody());
        payloadBuilder.setBadgeNumber(push.getBadge());
        payloadBuilder.setCategoryName(push.getCategory());
        payloadBuilder.setSound(push.getSound());
        payloadBuilder.setContentAvailable(isSilent);
        payloadBuilder.setThreadId(push.getCollapseKey());
        Map<String, Object> extras = push.getExtras();
        if (extras != null) {
            for (Map.Entry<String, Object> entry : extras.entrySet()) {
                payloadBuilder.addCustomProperty(entry.getKey(), entry.getValue());
            }
        }
        return payloadBuilder.buildWithDefaultMaximumLength();
    }
}