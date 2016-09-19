package com.thinkbiganalytics.jobrepo.service;

import com.thinkbiganalytics.jobrepo.model.ProvenanceEventSummaryStats;

import org.joda.time.DateTime;

import java.util.List;

/**
 * Created by sr186054 on 8/23/16.
 */
public interface ProvenanceEventStatisticsProvider {

    public static enum TimeFrame {

        FIVE_MIN(new Long(1000 * 60 * 5), "Last 5 Minutes"), TEN_MIN(new Long(1000 * 60 * 10), "Last 10 Minutes"), THIRTY_MIN(new Long(1000 * 60 * 30), "Last 30 Minutes"),
        HOUR(new Long(1000 * 60 * 60), "Last Hour"),
        THREE_HOUR(HOUR.millis * 3, "Last 3 Hours"), FIVE_HOUR(HOUR.millis * 5, "Last 5 Hours"), TEN_HOUR(HOUR.millis * 10, "Last 10 Hours"),
        DAY(HOUR.millis * 24, " Last Day"), WEEK(DAY.millis * 7, "Last Week"), MONTH(DAY.millis * 30, " Last Month"), YEAR(DAY.millis * 365, "Last Year");

        protected Long millis;
        private String displayName;

        private TimeFrame(long millis, String displayName) {
            this.millis = millis;
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public DateTime startTime() {
            return DateTime.now().minus(millis);
        }

        public DateTime startTimeRelativeTo(DateTime dt) {
            return dt.minus(millis);
        }
    }

    ProvenanceEventSummaryStats create(ProvenanceEventSummaryStats t);

    List<? extends ProvenanceEventSummaryStats> findWithinTimeWindow(DateTime start, DateTime end);

    List<? extends ProvenanceEventSummaryStats> findForFeed(String feedName, DateTime start, DateTime end);

    List<? extends ProvenanceEventSummaryStats> findForFeedProcessorStatistics(String feedName, DateTime start, DateTime end);

    List<? extends ProvenanceEventSummaryStats> findForFeedProcessorStatistics(String feedName, TimeFrame timeFrame);

    List<? extends ProvenanceEventSummaryStats> findForFeedStatisticsGroupedByTime(String feedName, DateTime start, DateTime end);

    List<? extends ProvenanceEventSummaryStats> findForFeedStatisticsGroupedByTime(String feedName, TimeFrame timeFrame);


}