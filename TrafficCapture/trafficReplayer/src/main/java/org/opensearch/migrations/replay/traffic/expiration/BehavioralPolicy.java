package org.opensearch.migrations.replay.traffic.expiration;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.Accumulation;

import java.time.Duration;
import java.time.Instant;

/**
 * I should look up what this is called in the Gang of Four book.
 * In my mind, this is a metaprogramming policy mixin.
 */
@Slf4j
public class BehavioralPolicy {
    private static String formatPartitionAndConnectionIds(String partitionId, String connectionId) {
        return connectionId + "[" + partitionId + "]";
    }

    public String appendageToDescribeHowToSetMinimumGuaranteedLifetime() {
        return null;
    }

    public void onDataArrivingBeforeTheStartOfTheCurrentProcessingWindow(
            String partitionId, String connectionId, Instant timestamp, Instant endOfWindow) {
        var hintString = appendageToDescribeHowToSetMinimumGuaranteedLifetime();
        log.error("Could not update the expiration of an object whose timestamp is before the " +
                "oldest point in time that packets are still being processed for this partition.  " +
                "This means that there was larger than expected temporal jitter in packets.  " +
                "The traffic for this connection may have already been reported as expired and processed " +
                "(potentially partially). This data may not be properly handled due to other data " +
                "within the connection being prematurely expired.  Trying to send the data through a new " +
                "instance of this object with a minimumGuaranteedLifetime of " +
                Duration.between(timestamp, endOfWindow) + " will allow for this packet to be properly " +
                "accumulated for (" + formatPartitionAndConnectionIds(partitionId, connectionId) + ")." +
                (hintString == null ? "" : "  " + hintString));
    }

    public void onNewDataArrivingAfterItsAccumulationHasBeenExpired(
            String partitionId, String connectionId, Instant packetTimestamp,
            long lastPacketTimestampMs, Instant endOfLastWindow, Duration minimumGuaranteedLifetime) {
        var extraGap = Duration.between(Instant.ofEpochMilli(lastPacketTimestampMs), packetTimestamp);
        var hintString = appendageToDescribeHowToSetMinimumGuaranteedLifetime();
        log.error("New data has arrived outside of the expiration window.  Data may be prematurely expired.  " +
                "The minimumGuaranteedLifetime for the ExpiringQueue should be increased from " +
                minimumGuaranteedLifetime + " to at least " + extraGap +
                (hintString == null ? "" : "  " + hintString));
        log.atInfo().setMessage(()->
                "New data has arrived for " + formatPartitionAndConnectionIds(partitionId, connectionId) +
                ", but during the processing of this Accumulation object, the Accumulation was expired.  " +
                "The maximum timestamp that would NOT have triggered expirations of previously observed data is " +
                endOfLastWindow + " and the last timestamp of the reference packet was " + packetTimestamp +
                ".  To remedy this, set the minimumGuaranteedLifetime to at least " + extraGap).log();
    }

    public boolean shouldRetryAfterAccumulationTimestampRaceDetected(String partitionId, String connectionId,
                                                                     Instant timestamp, Accumulation accumulation,
                                                                     int attempts) {
        if (attempts > ExpiringTrafficStreamMap.DEFAULT_NUM_TIMESTAMP_UPDATE_ATTEMPTS) {
            log.error("A race condition was detected while trying to update the most recent timestamp " +
                    "(" + timestamp + ") of " + "accumulation (" + accumulation + ") for " +
                    formatPartitionAndConnectionIds(partitionId, connectionId) +
                    ".  Giving up after " + attempts + " attempts.  Data for this connection may be corrupted.");
            return false;
        } else {
            return true;
        }
    }

    public void onNewDataArrivingAfterItsAccumulationHadBeenRemoved(String partitionId, String connectionId) {
        log.error("A race condition was detected that shows that while trying to add additional captured data for " +
                formatPartitionAndConnectionIds(partitionId, connectionId) +
                ", the accumulation was previously deleted.  Typically, the accumulation value will be purged from " +
                "the map before any such warning could even be detected.  However, there could still be a defect in " +
                "some of the caller's logic where remove() is being called prematurely.");
    }

    public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
        // do nothing by default
    }
}
