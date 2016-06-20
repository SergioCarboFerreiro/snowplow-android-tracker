/*
 * Copyright (c) 2015 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package com.snowplowanalytics.snowplow.tracker;

import android.content.Context;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.snowplowanalytics.snowplow.tracker.constants.TrackerConstants;
import com.snowplowanalytics.snowplow.tracker.constants.Parameters;
import com.snowplowanalytics.snowplow.tracker.events.Event;
import com.snowplowanalytics.snowplow.tracker.events.Timing;
import com.snowplowanalytics.snowplow.tracker.payload.TrackerPayload;
import com.snowplowanalytics.snowplow.tracker.utils.LogLevel;
import com.snowplowanalytics.snowplow.tracker.events.EcommerceTransaction;
import com.snowplowanalytics.snowplow.tracker.events.EcommerceTransactionItem;
import com.snowplowanalytics.snowplow.tracker.events.PageView;
import com.snowplowanalytics.snowplow.tracker.events.ScreenView;
import com.snowplowanalytics.snowplow.tracker.events.Structured;
import com.snowplowanalytics.snowplow.tracker.events.Unstructured;
import com.snowplowanalytics.snowplow.tracker.utils.Logger;
import com.snowplowanalytics.snowplow.tracker.payload.SelfDescribingJson;

/**
 * Builds a Tracker object which is used to
 * send events to a Snowplow Collector.
 */
public class Tracker {

    private final static String TAG = Tracker.class.getSimpleName();
    private final String trackerVersion = BuildConfig.TRACKER_LABEL;

    private static ScheduledExecutorService sessionExecutor;
    
    private Emitter emitter;
    private Subject subject;
    private Session trackerSession;
    private String namespace;
    private String appId;
    private boolean base64Encoded;
    private DevicePlatforms devicePlatform;
    private LogLevel level;
    private boolean sessionContext;
    private long sessionCheckInterval;
    private int threadCount;
    private TimeUnit timeUnit;

    private AtomicBoolean dataCollection = new AtomicBoolean(true);

    /**
     * Builder for the Tracker
     */
    public static class TrackerBuilder {

        protected final Emitter emitter; // Required
        protected final String namespace; // Required
        protected final String appId; // Required
        protected final Context context; // Required
        protected Subject subject = null; // Optional
        protected boolean base64Encoded = true; // Optional
        protected DevicePlatforms devicePlatform = DevicePlatforms.Mobile; // Optional
        protected LogLevel logLevel = LogLevel.OFF; // Optional
        protected boolean sessionContext = false; // Optional
        protected long foregroundTimeout = 600; // Optional - 10 minutes
        protected long backgroundTimeout = 300; // Optional - 5 minutes
        protected long sessionCheckInterval = 15; // Optional - 15 seconds
        protected int threadCount = 10; // Optional
        protected TimeUnit timeUnit = TimeUnit.SECONDS; // Optional

        /**
         * @param emitter Emitter to which events will be sent
         * @param namespace Identifier for the Tracker instance
         * @param appId Application ID
         * @param context The Android application context
         */
        public TrackerBuilder(Emitter emitter, String namespace, String appId, Context context) {
            this.emitter = emitter;
            this.namespace = namespace;
            this.appId = appId;
            this.context = context;
        }

        /**
         * @param subject Subject to be tracked
         * @return itself
         */
        public TrackerBuilder subject(Subject subject) {
            this.subject = subject;
            return this;
        }

        /**
         * @param base64 Whether JSONs in the payload should be base-64 encoded
         * @return itself
         */
        public TrackerBuilder base64(Boolean base64) {
            this.base64Encoded = base64;
            return this;
        }

        /**
         * @param platform The device platform the tracker is running on
         * @return itself
         */
        public TrackerBuilder platform(DevicePlatforms platform) {
            this.devicePlatform = platform;
            return this;
        }

        /**
         * @param log The log level for the Tracker class
         * @return itself
         */
        public TrackerBuilder level(LogLevel log) {
            this.logLevel = log;
            return this;
        }

        /**
         * @param sessionContext whether to add a session context
         * @return itself
         */
        public TrackerBuilder sessionContext(boolean sessionContext) {
            this.sessionContext = sessionContext;
            return this;
        }

        /**
         * @param timeout The session foreground timeout
         * @return itself
         */
        public TrackerBuilder foregroundTimeout(long timeout) {
            this.foregroundTimeout = timeout;
            return this;
        }

        /**
         * @param timeout The session background timeout
         * @return itself
         */
        public TrackerBuilder backgroundTimeout(long timeout) {
            this.backgroundTimeout = timeout;
            return this;
        }

        /**
         * @param sessionCheckInterval The session check interval
         * @return itself
         */
        public TrackerBuilder sessionCheckInterval(long sessionCheckInterval) {
            this.sessionCheckInterval = sessionCheckInterval;
            return this;
        }

        /**
         * @param threadCount the amount of threads to use for concurrency
         * @return itself
         */
        public TrackerBuilder threadCount(int threadCount) {
            this.threadCount = threadCount;
            return this;
        }

        /**
         * @param timeUnit a valid TimeUnit
         * @return itself
         */
        public TrackerBuilder timeUnit(TimeUnit timeUnit) {
            this.timeUnit = timeUnit;
            return this;
        }

        /**
         * Creates a new Tracker or throws an
         * Exception of we cannot find a suitable
         * extensible class.
         *
         * @return the new Tracker object
         */
        public Tracker build(){
            return new Tracker(this);
        }
    }

    /**
     * Creates a new Snowplow Tracker.
     *
     * @param builder The builder that constructs a tracker
     */
    private Tracker(TrackerBuilder builder) {
        this.emitter = builder.emitter;
        this.appId = builder.appId;
        this.base64Encoded = builder.base64Encoded;
        this.namespace = builder.namespace;
        this.subject = builder.subject;
        this.devicePlatform = builder.devicePlatform;
        this.level = builder.logLevel;
        this.sessionContext = builder.sessionContext;
        this.sessionCheckInterval = builder.sessionCheckInterval;
        this.threadCount = builder.threadCount < 2 ? 2 : builder.threadCount;
        this.timeUnit = builder.timeUnit;

        // If session context is True
        if (this.sessionContext) {
            this.trackerSession = new Session(
                builder.foregroundTimeout,
                builder.backgroundTimeout,
                builder.timeUnit,
                builder.context
            );
        }

        Executor.setThreadCount(this.threadCount);

        Logger.updateLogLevel(builder.logLevel);
        Logger.v(TAG, "Tracker created successfully.");
    }

    // --- Event Tracking Functions

    /**
     * Used for either Tracking a custom TrackerPayload or
     * for re-sending a failed event.
     *
     * @param payload the payload to track
     */
    public void track(TrackerPayload payload) {
        this.emitter.add(payload);
    }

    /**
     * Handles tracking the different types of events that
     * the Tracker can encounter.
     *
     * @param event the event to track
     */
    public void track(Event event) {
        if (!dataCollection.get()) {
            return;
        }

        List<SelfDescribingJson> context = event.getContext();

        // Figure out what type of event it is and track it!
        Class eClass = event.getClass();
        if (eClass.equals(PageView.class) || eClass.equals(Structured.class)) {
            this.addEventPayload((TrackerPayload) event.getPayload(), context);
        } else if (eClass.equals(EcommerceTransaction.class)) {
            this.addEventPayload((TrackerPayload) event.getPayload(), context);

            // Track each item individually
            EcommerceTransaction ecommerceTransaction = (EcommerceTransaction) event;
            for(EcommerceTransactionItem item : ecommerceTransaction.getItems()) {
                item.setTimestamp(ecommerceTransaction.getTimestamp());
                this.addEventPayload(item.getPayload(), item.getContext());
            }
        } else if (eClass.equals(Unstructured.class)) {

            // Need to set the Base64 rule for Unstructured events
            Unstructured unstructured = (Unstructured) event;
            unstructured.setBase64Encode(base64Encoded);
            this.addEventPayload(unstructured.getPayload(), context);
        } else if (eClass.equals(Timing.class) || eClass.equals(ScreenView.class)) {

            // These are wrapper classes for Unstructured events; need to create Unstructured
            // events from them and resend.
            this.track(Unstructured.builder()
                    .eventData((SelfDescribingJson) event.getPayload())
                    .customContext(context)
                    .timestamp(event.getTimestamp())
                    .eventId(event.getEventId())
                    .build());
        }
    }

    // --- Helpers

    /**
     * Builds and adds a finalized payload by adding in extra
     * information to the payload:
     * - The event contexts
     * - The Tracker Subject
     * - The Tracker parameters
     *
     * @param payload Payload the raw event payload to be
     *                decorated.
     * @param context The raw context list
     */
    private void addEventPayload(TrackerPayload payload, List<SelfDescribingJson> context) {

        // Add default parameters to the payload
        payload.add(Parameters.PLATFORM, this.devicePlatform.toString());
        payload.add(Parameters.APPID, this.appId);
        payload.add(Parameters.NAMESPACE, this.namespace);
        payload.add(Parameters.TRACKER_VERSION, this.trackerVersion);

        // If there is a subject present for the Tracker add it
        if (this.subject != null) {
            payload.addMap(new HashMap<String,Object>(this.subject.getSubject()));
        }

        // Build the final context and add it
        SelfDescribingJson envelope = getFinalContext(context);
        payload.addMap(envelope.getMap(), this.base64Encoded, Parameters.CONTEXT_ENCODED,
                Parameters.CONTEXT);

        // Add this payload to the emitter
        Logger.v(TAG, "Adding new payload to event storage: %s", payload);
        this.emitter.add(payload);
    }

    /**
     * Builds the final event context.
     *
     * @param context the base event context
     * @return the final event context json with
     *         many contexts inside
     */
    private SelfDescribingJson getFinalContext(List<SelfDescribingJson> context) {

        // Add session context
        if (this.sessionContext) {
            context.add(this.trackerSession.getSessionContext());
        }

        // Add subject context's
        if (this.subject != null) {
            if (!this.subject.getSubjectLocation().isEmpty()) {
                SelfDescribingJson locationPayload = new SelfDescribingJson(
                        TrackerConstants.GEOLOCATION_SCHEMA, this.subject.getSubjectLocation());
                context.add(locationPayload);
            }
            if (!this.subject.getSubjectMobile().isEmpty()) {
                SelfDescribingJson mobilePayload = new SelfDescribingJson(
                        TrackerConstants.MOBILE_SCHEMA, this.subject.getSubjectMobile());
                context.add(mobilePayload);
            }
        }

        // Convert List of SelfDescribingJson into a List of Map
        List<Map> contextMaps = new LinkedList<>();
        for (SelfDescribingJson selfDescribingJson : context) {
            contextMaps.add(selfDescribingJson.getMap());
        }

        // Return the contexts as a new SelfDescribingJson
        return new SelfDescribingJson(TrackerConstants.SCHEMA_CONTEXTS, contextMaps);
    }

    // --- Controls

    /**
     * Starts a polling session checker to
     * run at a defined interval.
     */
    public void resumeSessionChecking() {
        if (sessionExecutor == null && this.sessionContext) {
            Logger.d(TAG, "Session checking has been resumed.");
            final Session session = this.trackerSession;
            sessionExecutor = Executors.newSingleThreadScheduledExecutor();
            sessionExecutor.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    session.checkAndUpdateSession();
                }
            }, this.sessionCheckInterval, this.sessionCheckInterval, this.timeUnit);
        }
    }

    /**
     * Ends the polling session checker.
     */
    public void pauseSessionChecking() {
        if (sessionExecutor != null) {
            Logger.d(TAG, "Session checking has been paused.");
            sessionExecutor.shutdown();
            sessionExecutor = null;
        }
    }

    /**
     * Stops event collection and ends all
     * concurrent processes.
     */
    public void pauseEventTracking() {
        if (dataCollection.compareAndSet(true, false)) {
            pauseSessionChecking();
            getEmitter().shutdown();
        }
    }

    /**
     * Starts event collection processes
     * again.
     */
    public void resumeEventTracking() {
        if (dataCollection.compareAndSet(false, true)) {
            resumeSessionChecking();
            getEmitter().flush();
        }
    }

    // --- Setters

    /**
     * @param subject a valid subject object
     */
    public void setSubject(Subject subject) {
        this.subject = subject;
    }

    /**
     * @param emitter a valid emitter object
     */
    public void setEmitter(Emitter emitter) {
        // Need to shutdown prior emitter before updating
        getEmitter().shutdown();

        // Set the new emitter
        this.emitter = emitter;
    }

    /**
     * @param platform a valid DevicePlatforms object
     */
    public void setPlatform(DevicePlatforms platform) {
        this.devicePlatform = platform;
    }

    // --- Getters

    /**
     * @return the tracker version that was set
     */
    public String getTrackerVersion() {
        return this.trackerVersion;
    }

    /**
     * @return the trackers subject object
     */
    public Subject getSubject() {
        return this.subject;
    }

    /**
     * @return the emitter associated with the tracker
     */
    public Emitter getEmitter() {
        return this.emitter;
    }

    /**
     * @return the trackers namespace
     */
    public String getNamespace() {
        return this.namespace;
    }

    /**
     * @return the trackers set Application ID
     */
    public String getAppId() {
        return this.appId;
    }

    /**
     * @return the base64 setting of the tracker
     */
    public boolean getBase64Encoded() {
        return this.base64Encoded;
    }

    /**
     * @return the trackers device platform
     */
    public DevicePlatforms getPlatform() {
        return this.devicePlatform;
    }

    /**
     * @return the trackers logging level
     */
    public LogLevel getLogLevel() {
        return this.level;
    }

    /**
     * @return the trackers session object
     */
    public Session getSession() {
        return this.trackerSession;
    }

    /**
     * @return the state of data collection
     */
    public boolean getDataCollection() {
        return this.dataCollection.get();
    }

    /**
     * @return the amount of threads to use
     */
    public int getThreadCount() { return this.threadCount; }
}
