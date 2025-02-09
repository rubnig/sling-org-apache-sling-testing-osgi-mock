/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.testing.mock.osgi;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.commons.osgi.Order;
import org.apache.sling.commons.osgi.ServiceUtil;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock implementation of {@link EventAdmin}.
 * From {@link EventConstants} currently only {@link EventConstants#EVENT_TOPIC} is supported.
 */
@Component(immediate = true, service = EventAdmin.class)
public final class MockEventAdmin implements EventAdmin {

    @Reference(name="eventHandler", service=EventHandler.class,
            cardinality=ReferenceCardinality.MULTIPLE, policy=ReferencePolicy.DYNAMIC,
            bind="bindEventHandler", unbind="unbindEventHandler")
    private final Map<Object, EventHandlerItem> eventHandlers = new TreeMap<>();

    private ExecutorService asyncHandler;
    private BundleContext bundleContext;

    private static final Logger log = LoggerFactory.getLogger(MockEventAdmin.class);

    @Activate
    protected void activate(ComponentContext componentContext) {
        this.bundleContext = componentContext.getBundleContext();
        asyncHandler = Executors.newCachedThreadPool();
    }

    @Deactivate
    protected void deactivate(ComponentContext componentContext) {
        asyncHandler.shutdownNow();
    }

    @Override
    public void postEvent(final Event event) {
        if (log.isDebugEnabled()) {
            log.debug("Send event: {}, bundleContext={}", event.getTopic(), this.bundleContext);
        }
        try {
            asyncHandler.execute(new Runnable() {
                @Override
                public void run() {
                    distributeEvent(event);
                }
            });
        }
        catch (RejectedExecutionException ex) {
            // ignore
            log.debug("Ignore rejected execution: " + ex.getMessage(), ex);;
        }
    }

    @Override
    public void sendEvent(final Event event) {
        distributeEvent(event);
    }

    private void distributeEvent(Event event) {
        synchronized (eventHandlers) {
            for (EventHandlerItem item : eventHandlers.values()) {
                if (item.matches(event)) {
                    try {
                        if (log.isDebugEnabled()) {
                            log.debug("Distribute event: {} to {}, bundleContext={}", event.getTopic(), item.getEventHandler().getClass(), this.bundleContext);
                        }
                        item.getEventHandler().handleEvent(event);
                    }
                    catch (Throwable ex) {
                        log.error("Error handling event {} in {}", event, item.getEventHandler().getClass(), ex);
                    }
                }
            }
        }
    }

    protected void bindEventHandler(EventHandler eventHandler, Map<String, Object> props) {
        synchronized (eventHandlers) {
            eventHandlers.put(ServiceUtil.getComparableForServiceRanking(props, Order.DESCENDING), new EventHandlerItem(eventHandler, props));
        }
    }

    protected void unbindEventHandler(EventHandler eventHandler, Map<String, Object> props) {
        synchronized (eventHandlers) {
            eventHandlers.remove(ServiceUtil.getComparableForServiceRanking(props, Order.DESCENDING));
        }
    }

    private static class EventHandlerItem {

        private final EventHandler eventHandler;
        private final Pattern[] topicPatterns;

        private static final Pattern WILDCARD_PATTERN = Pattern.compile("[^*]+|(\\*)");

        public EventHandlerItem(EventHandler eventHandler, Map<String, Object> props) {
            this.eventHandler = eventHandler;
            topicPatterns = generateTopicPatterns(props.get(EventConstants.EVENT_TOPIC));
        }

        public boolean matches(Event event) {
            if (topicPatterns.length == 0) {
                return true;
            }
            String topic = event.getTopic();
            if (topic != null) {
                for (Pattern topicPattern : topicPatterns) {
                    if (topicPattern.matcher(topic).matches()) {
                        return true;
                    }
                }
            }
            return false;
        }

        public EventHandler getEventHandler() {
            return eventHandler;
        }

        private static Pattern[] generateTopicPatterns(Object topic) {
            String[] topics;
            if (topic == null) {
                topics = new String[0];
            }
            else if (topic instanceof String) {
                topics = new String[] { (String)topic };
            }
            else if (topic instanceof String[]) {
                topics = (String[])topic;
            }
            else {
                throw new IllegalArgumentException("Invalid topic: " + topic);
            }
            Pattern[] patterns = new Pattern[topics.length];
            for (int i=0; i<topics.length; i++) {
                patterns[i] = toWildcardPattern(topics[i]);
            }
            return patterns;
        }

        /**
         * Converts a wildcard string with * to a regex pattern (from http://stackoverflow.com/questions/24337657/wildcard-matching-in-java)
         * @param wildcard
         * @return Regexp pattern
         */
        private static Pattern toWildcardPattern(String wildcard) {
            Matcher matcher = WILDCARD_PATTERN.matcher(wildcard);
            StringBuffer result = new StringBuffer();
            while (matcher.find()) {
                if(matcher.group(1) != null) matcher.appendReplacement(result, ".*");
                else matcher.appendReplacement(result, "\\\\Q" + matcher.group(0) + "\\\\E");
            }
            matcher.appendTail(result);
            return Pattern.compile(result.toString());
        }

    }

}
