/**
 * Copyright © 2016-2018 The Thingsboard Authors
 *
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
 */
package org.thingsboard.rule.engine.action;

import com.datastax.driver.core.utils.UUIDs;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.AbstractListeningExecutorService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.commons.lang3.NotImplementedException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.thingsboard.rule.engine.api.*;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.dao.alarm.AlarmService;

import javax.script.ScriptException;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static org.thingsboard.rule.engine.action.TbAlarmNode.*;
import static org.thingsboard.server.common.data.alarm.AlarmSeverity.CRITICAL;
import static org.thingsboard.server.common.data.alarm.AlarmSeverity.WARNING;
import static org.thingsboard.server.common.data.alarm.AlarmStatus.*;

@RunWith(MockitoJUnitRunner.class)
public class TbAlarmNodeTest {

    private TbAlarmNode node;

    @Mock
    private TbContext ctx;
    @Mock
    private ListeningExecutor executor;
    @Mock
    private AlarmService alarmService;

    @Mock
    private ScriptEngine createJs;
    @Mock
    private ScriptEngine clearJs;
    @Mock
    private ScriptEngine detailsJs;

    private ListeningExecutor dbExecutor;

    private EntityId originator = new DeviceId(UUIDs.timeBased());
    private TenantId tenantId = new TenantId(UUIDs.timeBased());
    private TbMsgMetaData metaData = new TbMsgMetaData();
    private String rawJson = "{\"name\": \"Vit\", \"passed\": 5}";

    @Before
    public void before() {
        dbExecutor = new ListeningExecutor() {
            @Override
            public <T> ListenableFuture<T> executeAsync(Callable<T> task) {
                try {
                    return Futures.immediateFuture(task.call());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }

    @Test
    public void newAlarmCanBeCreated() throws ScriptException, IOException {
        initWithScript();
        metaData.putValue("key", "value");
        TbMsg msg = new TbMsg(UUIDs.timeBased(), "USER", originator, metaData, rawJson);

        when(createJs.executeFilter(msg)).thenReturn(true);
        when(detailsJs.executeJson(msg)).thenReturn(null);
        when(alarmService.findLatestByOriginatorAndType(tenantId, originator, "SomeType")).thenReturn(Futures.immediateFuture(null));

        doAnswer((Answer<Alarm>) invocationOnMock -> (Alarm) (invocationOnMock.getArguments())[0]).when(alarmService).createOrUpdateAlarm(any(Alarm.class));

        node.onMsg(ctx, msg);

        ArgumentCaptor<TbMsg> captor = ArgumentCaptor.forClass(TbMsg.class);
        verify(ctx).tellNext(captor.capture(), eq("Created"));
        TbMsg actualMsg = captor.getValue();

        assertEquals("ALARM", actualMsg.getType());
        assertEquals(originator, actualMsg.getOriginator());
        assertEquals("value", actualMsg.getMetaData().getValue("key"));
        assertEquals(Boolean.TRUE.toString(), actualMsg.getMetaData().getValue(IS_NEW_ALARM));
        assertNotSame(metaData, actualMsg.getMetaData());

        Alarm actualAlarm = new ObjectMapper().readValue(actualMsg.getData().getBytes(), Alarm.class);
        Alarm expectedAlarm = Alarm.builder()
                .tenantId(tenantId)
                .originator(originator)
                .status(ACTIVE_UNACK)
                .severity(CRITICAL)
                .propagate(true)
                .type("SomeType")
                .details(null)
                .build();

        assertEquals(expectedAlarm, actualAlarm);

        verify(executor, times(2)).executeAsync(any(Callable.class));
    }

    @Test
    public void shouldCreateScriptThrowsException() throws ScriptException {
        initWithScript();
        metaData.putValue("key", "value");
        TbMsg msg = new TbMsg(UUIDs.timeBased(), "USER", originator, metaData, rawJson);

        when(createJs.executeFilter(msg)).thenThrow(new NotImplementedException("message"));

        node.onMsg(ctx, msg);

        verifyError(msg, "message", NotImplementedException.class);


        verify(ctx).createJsScriptEngine("CREATE", "isAlarm");
        verify(ctx).createJsScriptEngine("CLEAR", "isCleared");
        verify(ctx).createJsScriptEngine("DETAILS", "Details");
        verify(ctx).getJsExecutor();
        verify(ctx).getDbCallbackExecutor();

        verifyNoMoreInteractions(ctx, alarmService, clearJs, detailsJs);
    }

    @Test
    public void buildDetailsThrowsException() throws ScriptException, IOException {
        initWithScript();
        metaData.putValue("key", "value");
        TbMsg msg = new TbMsg(UUIDs.timeBased(), "USER", originator, metaData, rawJson);

        when(createJs.executeFilter(msg)).thenReturn(true);
        when(detailsJs.executeJson(msg)).thenThrow(new NotImplementedException("message"));
        when(alarmService.findLatestByOriginatorAndType(tenantId, originator, "SomeType")).thenReturn(Futures.immediateFuture(null));

        node.onMsg(ctx, msg);

        verifyError(msg, "message", NotImplementedException.class);

        verify(ctx).createJsScriptEngine("CREATE", "isAlarm");
        verify(ctx).createJsScriptEngine("CLEAR", "isCleared");
        verify(ctx).createJsScriptEngine("DETAILS", "Details");
        verify(ctx, times(2)).getJsExecutor();
        verify(ctx).getAlarmService();
        verify(ctx, times(3)).getDbCallbackExecutor();
        verify(ctx).getTenantId();
        verify(alarmService).findLatestByOriginatorAndType(tenantId, originator, "SomeType");

        verifyNoMoreInteractions(ctx, alarmService, clearJs);
    }

    @Test
    public void ifAlarmClearedCreateNew() throws ScriptException, IOException {
        initWithScript();
        metaData.putValue("key", "value");
        TbMsg msg = new TbMsg(UUIDs.timeBased(), "USER", originator, metaData, rawJson);

        Alarm clearedAlarm = Alarm.builder().status(CLEARED_ACK).build();

        when(createJs.executeFilter(msg)).thenReturn(true);
        when(detailsJs.executeJson(msg)).thenReturn(null);
        when(alarmService.findLatestByOriginatorAndType(tenantId, originator, "SomeType")).thenReturn(Futures.immediateFuture(clearedAlarm));

        doAnswer((Answer<Alarm>) invocationOnMock -> (Alarm) (invocationOnMock.getArguments())[0]).when(alarmService).createOrUpdateAlarm(any(Alarm.class));

        node.onMsg(ctx, msg);

        ArgumentCaptor<TbMsg> captor = ArgumentCaptor.forClass(TbMsg.class);
        verify(ctx).tellNext(captor.capture(), eq("Created"));
        TbMsg actualMsg = captor.getValue();

        assertEquals("ALARM", actualMsg.getType());
        assertEquals(originator, actualMsg.getOriginator());
        assertEquals("value", actualMsg.getMetaData().getValue("key"));
        assertEquals(Boolean.TRUE.toString(), actualMsg.getMetaData().getValue(IS_NEW_ALARM));
        assertNotSame(metaData, actualMsg.getMetaData());

        Alarm actualAlarm = new ObjectMapper().readValue(actualMsg.getData().getBytes(), Alarm.class);
        Alarm expectedAlarm = Alarm.builder()
                .tenantId(tenantId)
                .originator(originator)
                .status(ACTIVE_UNACK)
                .severity(CRITICAL)
                .propagate(true)
                .type("SomeType")
                .details(null)
                .build();

        assertEquals(expectedAlarm, actualAlarm);

        verify(executor, times(2)).executeAsync(any(Callable.class));
    }

    @Test
    public void alarmCanBeUpdated() throws ScriptException, IOException {
        initWithScript();
        metaData.putValue("key", "value");
        TbMsg msg = new TbMsg(UUIDs.timeBased(), "USER", originator, metaData, rawJson);

        long oldEndDate = System.currentTimeMillis();
        Alarm activeAlarm = Alarm.builder().type("SomeType").tenantId(tenantId).originator(originator).status(ACTIVE_UNACK).severity(WARNING).endTs(oldEndDate).build();

        when(createJs.executeFilter(msg)).thenReturn(true);
        when(clearJs.executeFilter(msg)).thenReturn(false);
        when(detailsJs.executeJson(msg)).thenReturn(null);
        when(alarmService.findLatestByOriginatorAndType(tenantId, originator, "SomeType")).thenReturn(Futures.immediateFuture(activeAlarm));

        doAnswer((Answer<Alarm>) invocationOnMock -> (Alarm) (invocationOnMock.getArguments())[0]).when(alarmService).createOrUpdateAlarm(activeAlarm);

        node.onMsg(ctx, msg);

        ArgumentCaptor<TbMsg> captor = ArgumentCaptor.forClass(TbMsg.class);
        verify(ctx).tellNext(captor.capture(), eq("Updated"));
        TbMsg actualMsg = captor.getValue();

        assertEquals("ALARM", actualMsg.getType());
        assertEquals(originator, actualMsg.getOriginator());
        assertEquals("value", actualMsg.getMetaData().getValue("key"));
        assertEquals(Boolean.TRUE.toString(), actualMsg.getMetaData().getValue(IS_EXISTING_ALARM));
        assertNotSame(metaData, actualMsg.getMetaData());

        Alarm actualAlarm = new ObjectMapper().readValue(actualMsg.getData().getBytes(), Alarm.class);
        assertTrue(activeAlarm.getEndTs() > oldEndDate);
        Alarm expectedAlarm = Alarm.builder()
                .tenantId(tenantId)
                .originator(originator)
                .status(ACTIVE_UNACK)
                .severity(CRITICAL)
                .propagate(true)
                .type("SomeType")
                .details(null)
                .endTs(activeAlarm.getEndTs())
                .build();

        assertEquals(expectedAlarm, actualAlarm);

        verify(executor, times(2)).executeAsync(any(Callable.class));
    }

    @Test
    public void alarmCanBeCleared() throws ScriptException, IOException {
        initWithScript();
        metaData.putValue("key", "value");
        TbMsg msg = new TbMsg(UUIDs.timeBased(), "USER", originator, metaData, rawJson);

        long oldEndDate = System.currentTimeMillis();
        Alarm activeAlarm = Alarm.builder().type("SomeType").tenantId(tenantId).originator(originator).status(ACTIVE_UNACK).severity(WARNING).endTs(oldEndDate).build();

        when(createJs.executeFilter(msg)).thenReturn(false);
        when(clearJs.executeFilter(msg)).thenReturn(true);
//        when(detailsJs.executeJson(msg)).thenReturn(null);
        when(alarmService.findLatestByOriginatorAndType(tenantId, originator, "SomeType")).thenReturn(Futures.immediateFuture(activeAlarm));
        when(alarmService.clearAlarm(eq(activeAlarm.getId()), anyLong())).thenReturn(Futures.immediateFuture(true));
//        doAnswer((Answer<Alarm>) invocationOnMock -> (Alarm) (invocationOnMock.getArguments())[0]).when(alarmService).createOrUpdateAlarm(activeAlarm);

        node.onMsg(ctx, msg);

        ArgumentCaptor<TbMsg> captor = ArgumentCaptor.forClass(TbMsg.class);
        verify(ctx).tellNext(captor.capture(), eq("Cleared"));
        TbMsg actualMsg = captor.getValue();

        assertEquals("ALARM", actualMsg.getType());
        assertEquals(originator, actualMsg.getOriginator());
        assertEquals("value", actualMsg.getMetaData().getValue("key"));
        assertEquals(Boolean.TRUE.toString(), actualMsg.getMetaData().getValue(IS_CLEARED_ALARM));
        assertNotSame(metaData, actualMsg.getMetaData());

        Alarm actualAlarm = new ObjectMapper().readValue(actualMsg.getData().getBytes(), Alarm.class);
        Alarm expectedAlarm = Alarm.builder()
                .tenantId(tenantId)
                .originator(originator)
                .status(CLEARED_UNACK)
                .severity(WARNING)
                .propagate(false)
                .type("SomeType")
                .details(null)
                .endTs(oldEndDate)
                .build();

        assertEquals(expectedAlarm, actualAlarm);
    }

    private void initWithScript() {
        try {
            TbAlarmNodeConfiguration config = new TbAlarmNodeConfiguration();
            config.setPropagate(true);
            config.setSeverity(CRITICAL);
            config.setAlarmType("SomeType");
            config.setCreateConditionJs("CREATE");
            config.setClearConditionJs("CLEAR");
            config.setAlarmDetailsBuildJs("DETAILS");
            ObjectMapper mapper = new ObjectMapper();
            TbNodeConfiguration nodeConfiguration = new TbNodeConfiguration(mapper.valueToTree(config));

            when(ctx.createJsScriptEngine("CREATE", "isAlarm")).thenReturn(createJs);
            when(ctx.createJsScriptEngine("CLEAR", "isCleared")).thenReturn(clearJs);
            when(ctx.createJsScriptEngine("DETAILS", "Details")).thenReturn(detailsJs);

            when(ctx.getTenantId()).thenReturn(tenantId);
            when(ctx.getJsExecutor()).thenReturn(executor);
            when(ctx.getAlarmService()).thenReturn(alarmService);
            when(ctx.getDbCallbackExecutor()).thenReturn(dbExecutor);

            mockJsExecutor();

            node = new TbAlarmNode();
            node.init(ctx, nodeConfiguration);
        } catch (TbNodeException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void mockJsExecutor() {
        when(ctx.getJsExecutor()).thenReturn(executor);
        doAnswer((Answer<ListenableFuture<Boolean>>) invocationOnMock -> {
            try {
                Callable task = (Callable) (invocationOnMock.getArguments())[0];
                return Futures.immediateFuture((Boolean) task.call());
            } catch (Throwable th) {
                return Futures.immediateFailedFuture(th);
            }
        }).when(executor).executeAsync(any(Callable.class));
    }

    private void verifyError(TbMsg msg, String message, Class expectedClass) {
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(ctx).tellError(same(msg), captor.capture());

        Throwable value = captor.getValue();
        assertEquals(expectedClass, value.getClass());
        assertEquals(message, value.getMessage());
    }

}