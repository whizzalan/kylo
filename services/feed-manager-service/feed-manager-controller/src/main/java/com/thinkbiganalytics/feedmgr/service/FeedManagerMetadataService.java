package com.thinkbiganalytics.feedmgr.service;

/*-
 * #%L
 * thinkbig-feed-manager-controller
 * %%
 * Copyright (C) 2017 ThinkBig Analytics
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.thinkbiganalytics.datalake.authorization.service.HadoopAuthorizationService;
import com.thinkbiganalytics.feedmgr.InvalidOperationException;
import com.thinkbiganalytics.feedmgr.rest.model.FeedCategory;
import com.thinkbiganalytics.feedmgr.rest.model.FeedMetadata;
import com.thinkbiganalytics.feedmgr.rest.model.FeedSummary;
import com.thinkbiganalytics.feedmgr.rest.model.NifiFeed;
import com.thinkbiganalytics.feedmgr.rest.model.RegisteredTemplate;
import com.thinkbiganalytics.feedmgr.rest.model.UIFeed;
import com.thinkbiganalytics.feedmgr.rest.model.UserFieldCollection;
import com.thinkbiganalytics.feedmgr.rest.model.UserProperty;
import com.thinkbiganalytics.feedmgr.security.FeedsAccessControl;
import com.thinkbiganalytics.feedmgr.service.category.FeedManagerCategoryService;
import com.thinkbiganalytics.feedmgr.service.feed.FeedManagerFeedService;
import com.thinkbiganalytics.feedmgr.service.feed.FeedModelTransform;
import com.thinkbiganalytics.feedmgr.service.template.FeedManagerTemplateService;
import com.thinkbiganalytics.metadata.api.MetadataAccess;
import com.thinkbiganalytics.metadata.api.event.MetadataEventListener;
import com.thinkbiganalytics.metadata.api.event.MetadataEventService;
import com.thinkbiganalytics.metadata.api.event.feed.CleanupTriggerEvent;
import com.thinkbiganalytics.metadata.api.event.feed.FeedOperationStatusEvent;
import com.thinkbiganalytics.metadata.api.feed.Feed;
import com.thinkbiganalytics.metadata.api.feedmgr.feed.FeedManagerFeed;
import com.thinkbiganalytics.metadata.api.op.FeedOperation;
import com.thinkbiganalytics.nifi.rest.client.LegacyNifiRestClient;
import com.thinkbiganalytics.nifi.rest.client.NiFiComponentState;
import com.thinkbiganalytics.nifi.rest.client.NiFiRestClient;
import com.thinkbiganalytics.nifi.rest.model.NifiProperty;
import com.thinkbiganalytics.nifi.rest.support.NifiProcessUtil;
import com.thinkbiganalytics.security.AccessController;

import org.apache.nifi.web.api.dto.ConnectionDTO;
import org.apache.nifi.web.api.dto.ProcessGroupDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;

/**
 * Provides access to category, feed, and template metadata stored in the metadata store.
 */
public class FeedManagerMetadataService implements MetadataService {

    private static final Logger log = LoggerFactory.getLogger(FeedManagerMetadataService.class);

    @Inject
    FeedManagerCategoryService categoryProvider;

    @Inject
    FeedManagerTemplateService templateProvider;

    @Inject
    FeedManagerFeedService feedProvider;

    @Inject
    LegacyNifiRestClient nifiRestClient;

    @Inject
    MetadataAccess metadataAccess;

    @Inject
    FeedModelTransform feedModelTransform;
    
    @Inject
    private AccessController accessController;

    // Metadata event service
    @Inject
    private MetadataEventService eventService;

    // I had to use autowired instead of Inject to allow null values.
    @Autowired(required = false)
    @Qualifier("hadoopAuthorizationService")
    private HadoopAuthorizationService hadoopAuthorizationService;

    /**
     * NiFi REST client
     */
    @Inject
    private NiFiRestClient nifiClient;

    @Override
    public RegisteredTemplate registerTemplate(RegisteredTemplate registeredTemplate) {
        return templateProvider.registerTemplate(registeredTemplate);
    }

    @Override
    public List<NifiProperty> getTemplateProperties(String templateId) {
        return templateProvider.getTemplateProperties(templateId);
    }





    public void deleteRegisteredTemplate(String templateId) {
        templateProvider.deleteRegisteredTemplate(templateId);
    }

    @Override
    public List<RegisteredTemplate> getRegisteredTemplates() {
        return templateProvider.getRegisteredTemplates();
    }

    @Override
    public NifiFeed createFeed(FeedMetadata feedMetadata) {
        NifiFeed feed = feedProvider.createFeed(feedMetadata);
        if (feed.isSuccess()) {
            if (feed.isEnableAfterSave()) {
                enableFeed(feed.getFeedMetadata().getId());
            }
            //requery to get the latest version
            FeedMetadata updatedFeed = getFeedById(feed.getFeedMetadata().getId());
            feed.setFeedMetadata(updatedFeed);
        }
        return feed;

    }

    @Override
    public void saveFeed(FeedMetadata feed) {
        feedProvider.saveFeed(feed);
    }

    @Override
    public void deleteFeed(@Nonnull final String feedId) {
        // First check if this should be allowed.
        this.accessController.checkPermission(AccessController.SERVICES, FeedsAccessControl.ADMIN_FEEDS);

        // Step 1: Fetch feed metadata
        final FeedMetadata feed = feedProvider.getFeedById(feedId);
        if (feed == null) {
            throw new IllegalArgumentException("Unknown feed: " + feedId);
        }

        // Step 2: Check for dependent feeds
        if (feed.getUsedByFeeds() != null && !feed.getUsedByFeeds().isEmpty()) {
            final List<String> systemNames = feed.getUsedByFeeds().stream().map(FeedSummary::getCategoryAndFeedSystemName).collect(Collectors.toList());
            throw new IllegalStateException("Feed is referenced by " + feed.getUsedByFeeds().size() + " other feeds: " + systemNames);
        }

        // Step 3: Delete hadoop authorization security policies if they exists
        if (hadoopAuthorizationService != null) {
            metadataAccess.read(() -> {
                FeedManagerFeed domainFeed = feedModelTransform.feedToDomain(feed);
                String hdfsPaths = (String) domainFeed.getProperties().get(HadoopAuthorizationService.REGISTRATION_HDFS_FOLDERS);

                hadoopAuthorizationService.deleteHivePolicy(feed.getSystemCategoryName(), feed.getSystemFeedName());
                hadoopAuthorizationService.deleteHdfsPolicy(feed.getSystemCategoryName(), feed.getSystemFeedName(), HadoopAuthorizationService.convertNewlineDelimetedTextToList(hdfsPaths));
            });

        }

        // Step 4: Enable NiFi cleanup flow
        boolean needsCleanup = false;
        final ProcessGroupDTO feedProcessGroup;
        final ProcessGroupDTO categoryProcessGroup = nifiRestClient.getProcessGroupByName("root", feed.getSystemCategoryName(), false, true);

        if (categoryProcessGroup != null) {
            feedProcessGroup = NifiProcessUtil.findFirstProcessGroupByName(categoryProcessGroup.getContents().getProcessGroups(), feed.getSystemFeedName());
            if (feedProcessGroup != null) {
                needsCleanup = nifiRestClient.setInputAsRunningByProcessorMatchingType(feedProcessGroup.getId(), "com.thinkbiganalytics.nifi.v2.metadata.TriggerCleanup");
            }
        }

        // Step 5: Run NiFi cleanup flow
        if (needsCleanup) {
            // Wait for input processor to start
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                // ignored
            }

            cleanupFeed(feed);
        }

        // Step 6: Remove feed from NiFi
        if (categoryProcessGroup != null) {
            final Set<ConnectionDTO> connections = categoryProcessGroup.getContents().getConnections();
            for (ProcessGroupDTO processGroup : NifiProcessUtil.findProcessGroupsByFeedName(categoryProcessGroup.getContents().getProcessGroups(), feed.getSystemFeedName())) {
                nifiRestClient.deleteProcessGroupAndConnections(processGroup, connections);
            }
        }

        // Step 7: Delete database entries
        feedProvider.deleteFeed(feedId);

    }

    /**
     * Changes the state of the specified feed.
     *
     * @param feedSummary the feed
     * @param state       the new state
     * @return {@code true} if the feed is in the new state, or {@code false} otherwise
     */
    private boolean updateNifiFeedRunningStatus(FeedSummary feedSummary, Feed.State state) {
        // Validate parameters
        if (feedSummary == null || !feedSummary.getState().equals(state.name())) {
            return false;
        }

        // Find the process group
        final Optional<ProcessGroupDTO> categoryGroup = nifiClient.processGroups().findByName("root", feedSummary.getSystemCategoryName(), false, false);
        final Optional<ProcessGroupDTO> feedGroup = categoryGroup.flatMap(group -> nifiClient.processGroups().findByName(group.getId(), feedSummary.getSystemFeedName(), false, true));
        if (!feedGroup.isPresent()) {
            log.warn("NiFi process group missing for feed: {}.{}", feedSummary.getSystemCategoryName(), feedSummary.getSystemFeedName());
            return Feed.State.DISABLED.equals(state);
        }

        // Update the state
        if (state.equals(Feed.State.ENABLED)) {
            nifiClient.processGroups().schedule(feedGroup.get().getId(), categoryGroup.get().getId(), NiFiComponentState.RUNNING);
        } else if (state.equals(Feed.State.DISABLED)) {
            nifiRestClient.stopInputs(feedGroup.get());
        }

        return true;
    }

    public FeedSummary enableFeed(String feedId) {
        return metadataAccess.commit(() -> {
            FeedMetadata feedMetadata = feedProvider.getFeedById(feedId);
            if (!feedMetadata.getState().equals(Feed.State.ENABLED.name())) {
                FeedSummary feedSummary = feedProvider.enableFeed(feedId);

                boolean updatedNifi = updateNifiFeedRunningStatus(feedSummary, Feed.State.ENABLED);
                if (!updatedNifi) {
                    //rollback
                    throw new RuntimeException("Unable to enable Feed " + feedId);
                }
                return feedSummary;
            }
            return new FeedSummary(feedMetadata);
        });

    }

    public FeedSummary disableFeed(final String feedId) {
        return metadataAccess.commit(() -> {
            FeedMetadata feedMetadata = feedProvider.getFeedById(feedId);
            if (!feedMetadata.getState().equals(Feed.State.DISABLED.name())) {
                FeedSummary feedSummary = feedProvider.disableFeed(feedId);
                boolean updatedNifi = updateNifiFeedRunningStatus(feedSummary, Feed.State.DISABLED);
                if (!updatedNifi) {
                    //rollback
                    throw new RuntimeException("Unable to disable Feed " + feedId);
                }
                return feedSummary;
            }
            return new FeedSummary(feedMetadata);
        });
    }

    @Override
    public Collection<FeedMetadata> getFeeds() {
        return feedProvider.getFeeds();
    }

    @Override
    public Collection<? extends UIFeed> getFeeds(boolean verbose) {
        return feedProvider.getFeeds(verbose);
    }

    @Override
    public List<FeedSummary> getFeedSummaryData() {
        return feedProvider.getFeedSummaryData();
    }

    @Override
    public List<FeedSummary> getFeedSummaryForCategory(String categoryId) {
        return feedProvider.getFeedSummaryForCategory(categoryId);
    }

    @Override
    public FeedMetadata getFeedByName(String categoryName, String feedName) {
        return feedProvider.getFeedByName(categoryName, feedName);
    }

    @Override
    public FeedMetadata getFeedById(String feedId) {
        return feedProvider.getFeedById(feedId);
    }

    @Override
    public FeedMetadata getFeedById(String feedId, boolean refreshTargetTableSchema) {
        return feedProvider.getFeedById(feedId, refreshTargetTableSchema);
    }

    @Override
    public Collection<FeedCategory> getCategories() {
        return categoryProvider.getCategories();
    }

    @Override
    public FeedCategory getCategoryBySystemName(String name) {
        return categoryProvider.getCategoryBySystemName(name);
    }

    @Override
    public void saveCategory(FeedCategory category) {
        categoryProvider.saveCategory(category);
    }

    @Override
    public boolean deleteCategory(String categoryId) throws InvalidOperationException {
        return categoryProvider.deleteCategory(categoryId);
    }

    /**
     * Runs the cleanup flow for the specified feed.
     *
     * @param feed the feed to be cleaned up
     * @throws FeedCleanupFailedException  if the cleanup flow was started but failed to complete successfully
     * @throws FeedCleanupTimeoutException if the cleanup flow was started but failed to complete in the allotted time
     * @throws RuntimeException            if the cleanup flow could not be started
     */
    private void cleanupFeed(@Nonnull final FeedMetadata feed) {
        // Create event listener
        final FeedCompletionListener listener = new FeedCompletionListener(feed, Thread.currentThread());
        eventService.addListener(listener);

        try {
            // Trigger cleanup
            feedProvider.enableFeedCleanup(feed.getId());
            eventService.notify(new CleanupTriggerEvent(feedProvider.resolveFeed(feed.getId())));

            // Wait for completion
            long remaining = 60000L;
            while (remaining > 0 && (listener.getState() == null || listener.getState() == FeedOperation.State.STARTED)) {
                final long start = System.currentTimeMillis();
                try {
                    Thread.sleep(remaining);
                } catch (InterruptedException e) {
                    // ignored
                }
                remaining -= System.currentTimeMillis() - start;
            }
        } finally {
            eventService.removeListener(listener);
        }

        // Check result
        if (listener.getState() == null || listener.getState() == FeedOperation.State.STARTED) {
            throw new FeedCleanupTimeoutException("Cleanup timed out for feed: " + feed.getId());
        }
        if (listener.getState() != FeedOperation.State.SUCCESS) {
            throw new FeedCleanupFailedException("Cleanup state " + listener.getState() + " for feed: " + feed.getId());
        }
    }

    @Nonnull
    @Override
    public Set<UserProperty> getCategoryUserFields() {
        return categoryProvider.getUserProperties();
    }

    @Nonnull
    @Override
    public Optional<Set<UserProperty>> getFeedUserFields(@Nonnull final String categoryId) {
        return feedProvider.getUserFields(categoryId);
    }

    @Nonnull
    @Override
    public UserFieldCollection getUserFields() {
        final UserFieldCollection collection = new UserFieldCollection();
        collection.setCategoryFields(categoryProvider.getUserFields());
        collection.setFeedFields(feedProvider.getUserFields());
        return collection;
    }

    @Override
    public void setUserFields(@Nonnull final UserFieldCollection userFields) {
        categoryProvider.setUserFields(userFields.getCategoryFields());
        feedProvider.setUserFields(userFields.getFeedFields());
    }

    /**
     * Listens for a feed completion then interrupts a target thread.
     */
    private static class FeedCompletionListener implements MetadataEventListener<FeedOperationStatusEvent> {

        /**
         * Name of the feed to watch for
         */
        @Nonnull
        private final String feedName;
        /**
         * Thread to interrupt
         */
        @Nonnull
        private final Thread target;
        /**
         * Current state of the feed
         */
        @Nullable
        private FeedOperation.State state;

        /**
         * Constructs a {@code FeedCompletionListener} that listens for events for the specified feed then interrupts the specified thread.
         *
         * @param feed   the feed to watch far
         * @param target the thread to interrupt
         */
        FeedCompletionListener(@Nonnull final FeedMetadata feed, @Nonnull final Thread target) {
            this.feedName = feed.getCategoryAndFeedName();
            this.target = target;
        }

        /**
         * Gets the current state of the feed.
         *
         * @return the feed state
         */
        @Nullable
        public FeedOperation.State getState() {
            return state;
        }

        @Override
        public void notify(@Nonnull final FeedOperationStatusEvent event) {
            if (event.getData().getFeedName().equals(feedName)) {
                state = event.getData().getState();
                target.interrupt();
            }
        }

    }
}
