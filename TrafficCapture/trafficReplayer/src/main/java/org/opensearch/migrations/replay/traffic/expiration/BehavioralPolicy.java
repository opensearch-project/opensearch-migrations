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
    public void onDataArrivingBeforeTheStartOfTheCurrentProcessingWindow(
            String partitionId, String connectionId, Instant timestamp, Instant endOfWindow) {
        log.error("Could not update the expiration of an object whose timestamp is before the " +
                "oldest point in time that packets are still being processed for this partition.  " +
                "This means that there was larger than expected temporal jitter in packets.  " +
                "That means that traffic for this connection may have already been reported as expired " +
                "and processed as such and that this data will not be properly handled due to other data " +
                "within the connection being prematurely expired.  Trying to send the data through a new " +
                "instance of this object with a minimumGuaranteedLifetime of " +
                Duration.between(timestamp, endOfWindow) + " will allow for this packet to be properly " +
                "accumulated for (" + partitionId + "," + connectionId + ")");
    }

    public void onNewDataArrivingAfterItsAccumulationHasBeenExpired(
            String partitionId, String connectionId, Instant lastPacketTimestamp, Instant endOfWindow) {
        log.error("New data has arrived, but during the processing of this Accumulation object, " +
                "the Accumulation was expired.  This indicates that the minimumGuaranteedLifetime " +
                "must be set to at least " + Duration.between(lastPacketTimestamp, endOfWindow) +
                ".  The beginning of the valid time window is currently " + endOfWindow +
                " for (" + partitionId + "," + connectionId + ") and the last timestamp of the " +
                "Accumulation object that was being assembled was");
    }

    public void onNewDataArrivingWithATimestampThatIsAlreadyExpired(
            String partitionId, String connectionId, Instant timestamp, Instant endOfWindow) {
        log.error("New data has arrived, but during the processing of this Accumulation object, " +
                "the Accumulation was expired.  This indicates that the minimumGuaranteedLifetime " +
                "must be set to at least " + Duration.between(timestamp, endOfWindow) +
                ".  The beginning of the valid time window is currently " + endOfWindow +
                " for (" + partitionId + "," + connectionId + ") and the last timestamp of the " +
                "Accumulation object that was being assembled was");
    }

    public boolean shouldRetryAfterAccumulationTimestampRaceDetected(String partitionId, String connectionId,
                                                                     Instant timestamp, Accumulation accumulation,
                                                                     int attempts) {
        if (attempts > ExpiringTrafficStreamMap.DEFAULT_NUM_TIMESTAMP_UPDATE_ATTEMPTS) {
            log.error("A race condition was detected while trying to update the most recent timestamp " +
                    "(" + timestamp + ") of " + "accumulation (" + accumulation + ") for " +
                    partitionId + "/" + connectionId + ".  Giving up after " + attempts + " attempts.  " +
                    "Data for this connection may be corrupted.");
            return false;
        } else {
            return true;
        }
    }

    public void onExpireAccumulation(String partitionId, String connectionId, Accumulation accumulation) {
        // do nothing by default
    }
}
