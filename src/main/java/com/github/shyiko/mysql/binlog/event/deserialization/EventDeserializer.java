/*
 * Copyright 2013 Stanley Shyiko
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
package com.github.shyiko.mysql.binlog.event.deserialization;

import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeader;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author <a href="mailto:stanley.shyiko@gmail.com">Stanley Shyiko</a>
 */
public class EventDeserializer {

    private final EventHeaderDeserializer eventHeaderDeserializer;
    private final EventDataDeserializer defaultEventDataDeserializer;
    private final Map<EventType, EventDataDeserializer> eventDataDeserializers;

    private int checksumLength;

    private final Map<Long, TableMapEventData> tableMapEventByTableId;

    public EventDeserializer(
            EventHeaderDeserializer eventHeaderDeserializer,
            EventDataDeserializer defaultEventDataDeserializer,
            Map<EventType, EventDataDeserializer> eventDataDeserializers,
            Map<Long, TableMapEventData> tableMapEventByTableId
    ) {
        this.eventHeaderDeserializer = eventHeaderDeserializer;
        this.defaultEventDataDeserializer = defaultEventDataDeserializer;
        this.tableMapEventByTableId = tableMapEventByTableId;
        this.eventDataDeserializers = eventDataDeserializers;
    }

    public EventDeserializer() {
        this.eventHeaderDeserializer = new EventHeaderV4Deserializer();
        this.defaultEventDataDeserializer = new NullEventDataDeserializer();
        this.tableMapEventByTableId = new HashMap<Long, TableMapEventData>();
        this.eventDataDeserializers = new HashMap<EventType, EventDataDeserializer>();
        eventDataDeserializers.put(EventType.FORMAT_DESCRIPTION, new FormatDescriptionEventDataDeserializer());
        eventDataDeserializers.put(EventType.ROTATE, new RotateEventDataDeserializer());
        eventDataDeserializers.put(EventType.QUERY, new QueryEventDataDeserializer());
        eventDataDeserializers.put(EventType.TABLE_MAP, new TableMapEventDataDeserializer());
        eventDataDeserializers.put(EventType.XID, new XidEventDataDeserializer());
        eventDataDeserializers.put(EventType.WRITE_ROWS,
                new WriteRowsEventDataDeserializer(tableMapEventByTableId));
        eventDataDeserializers.put(EventType.UPDATE_ROWS,
                new UpdateRowsEventDataDeserializer(tableMapEventByTableId));
        eventDataDeserializers.put(EventType.DELETE_ROWS,
                new DeleteRowsEventDataDeserializer(tableMapEventByTableId));
        eventDataDeserializers.put(EventType.EXT_WRITE_ROWS,
                new WriteRowsEventDataDeserializer(tableMapEventByTableId).
                        setMayContainExtraInformation(true));
        eventDataDeserializers.put(EventType.EXT_UPDATE_ROWS,
                new UpdateRowsEventDataDeserializer(tableMapEventByTableId).
                        setMayContainExtraInformation(true));
        eventDataDeserializers.put(EventType.EXT_DELETE_ROWS,
                new DeleteRowsEventDataDeserializer(tableMapEventByTableId).
                        setMayContainExtraInformation(true));
        eventDataDeserializers.put(EventType.ROWS_QUERY, new RowsQueryEventDataDeserializer());
        eventDataDeserializers.put(EventType.GTID, new GtidEventDataDeserializer());
    }

    public void setEventDataDeserializer(EventType eventType, EventDataDeserializer eventDataDeserializer) {
        eventDataDeserializers.put(eventType, eventDataDeserializer);
    }

    public void setChecksumType(ChecksumType checksumType) {
        this.checksumLength = checksumType.getLength();
    }

    /**
     * @return deserialized event or null in case of end-of-stream
     */
    public Event nextEvent(ByteArrayInputStream inputStream) throws IOException {
        if (inputStream.peek() == -1) {
            return null;
        }
        EventHeader eventHeader = eventHeaderDeserializer.deserialize(inputStream);
        EventData eventData = deserializeEventData(inputStream, eventHeader);
        if (eventHeader.getEventType() == EventType.TABLE_MAP) {
            TableMapEventData tableMapEvent = (TableMapEventData) eventData;
            tableMapEventByTableId.put(tableMapEvent.getTableId(), tableMapEvent);
        }
        return new Event(eventHeader, eventData);
    }

    private EventData deserializeEventData(ByteArrayInputStream inputStream, EventHeader eventHeader)
            throws EventDataDeserializationException {
        EventDataDeserializer eventDataDeserializer = getEventDataDeserializer(eventHeader.getEventType());
        // todo: use checksum algorithm descriptor from FormatDescriptionEvent
        // (as per http://dev.mysql.com/worklog/task/?id=2540)
        int eventBodyLength = (int) eventHeader.getDataLength() - checksumLength;
        EventData eventData;
        try {
            inputStream.enterBlock(eventBodyLength);
            try {
                eventData = eventDataDeserializer.deserialize(inputStream);
            } finally {
                inputStream.skipToTheEndOfTheBlock();
                inputStream.skip(checksumLength);
            }
        } catch (IOException e) {
            throw new EventDataDeserializationException(eventHeader, e);
        }
        return eventData;
    }

    private EventDataDeserializer getEventDataDeserializer(EventType eventType) {
        EventDataDeserializer eventDataDeserializer = eventDataDeserializers.get(eventType);
        return eventDataDeserializer != null ? eventDataDeserializer : defaultEventDataDeserializer;
    }

}
