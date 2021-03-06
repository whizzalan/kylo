package com.thinkbiganalytics.nifi.provenance;

/*-
 * #%L
 * thinkbig-nifi-provenance-repo
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

import com.thinkbiganalytics.nifi.provenance.model.ProvenanceEventRecordDTO;

import org.apache.nifi.provenance.ProvenanceEventRecord;
import org.apache.nifi.util.FormatUtils;
import org.joda.time.DateTime;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Convert a {@link ProvenanceEventRecord} to a {@link ProvenanceEventRecordDTO} object
 */
public class ProvenanceEventRecordConverter implements Serializable {


    public static ProvenanceEventRecordDTO getPooledObject(ProvenanceEventObjectPool pool, final ProvenanceEventRecord event) throws Exception {
        ProvenanceEventRecordDTO dto = pool.borrowObject();
        populateEvent(dto, event);
        return dto;

    }


    public static void populateEvent(ProvenanceEventRecordDTO dto, ProvenanceEventRecord event) {

        final Map<String, String> updatedAttrs = event.getUpdatedAttributes();
        final Map<String, String> previousAttrs = event.getPreviousAttributes();

        dto.setId(String.valueOf(event.getEventId()));
        dto.setEventId(event.getEventId());
        dto.setEventTime(new DateTime(event.getEventTime()));
        dto.setEventType(event.getEventType().name());
        dto.setFileSize(FormatUtils.formatDataSize(event.getFileSize()));
        dto.setFileSizeBytes(event.getFileSize());
        dto.setComponentId(event.getComponentId());
        dto.setComponentType(event.getComponentType());
        dto.setFlowFileUuid(event.getFlowFileUuid());
        dto.setDetails(event.getDetails());
        dto.setRelationship(event.getRelationship());

        dto.setUpdatedAttributes(updatedAttrs);
        dto.setPreviousAttributes(previousAttrs);
        dto.setAttributeMap(event.getAttributes());

        dto.setInputContentClaimFileSizeBytes(event.getPreviousFileSize());
        dto.setOutputContentClaimFileSize(FormatUtils.formatDataSize(event.getFileSize()));
        dto.setOutputContentClaimFileSizeBytes(event.getFileSize());

        if (event.getPreviousFileSize() != null) {
            dto.setInputContentClaimFileSize(FormatUtils.formatDataSize(event.getPreviousFileSize()));
        }

        dto.setSourceConnectionIdentifier(event.getSourceQueueIdentifier());

        dto.setStartTime(new DateTime(event.getLineageStartDate()));

        final List<String> parentUuids = new ArrayList<>(event.getParentUuids());
        dto.setParentUuids(parentUuids);

        final List<String> childUuids = new ArrayList<>(event.getChildUuids());
        dto.setChildUuids(childUuids);
    }

}
