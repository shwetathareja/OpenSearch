/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.indices.streamingingestion.pause;

import org.opensearch.action.admin.indices.streamingingestion.IngestionStateShardFailure;
import org.opensearch.action.admin.indices.streamingingestion.IngestionUpdateStateResponse;
import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/**
 * Transport response for pausing ingestion.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class PauseIngestionResponse extends IngestionUpdateStateResponse {

    PauseIngestionResponse(StreamInput in) throws IOException {
        super(in);
    }

    public PauseIngestionResponse(
        final boolean acknowledged,
        final boolean shardsAcknowledged,
        final IngestionStateShardFailure[] shardFailuresList,
        String errorMessage
    ) {
        super(acknowledged, shardsAcknowledged, shardFailuresList, errorMessage);
    }
}
