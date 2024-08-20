/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.hertzbeat.manager.controller;

import static org.apache.hertzbeat.common.constants.CommonConstants.FAIL_CODE;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import com.fasterxml.jackson.core.type.TypeReference;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.common.entity.dto.Message;
import org.apache.hertzbeat.common.entity.manager.Monitor;
import org.apache.hertzbeat.common.entity.manager.bulletin.Bulletin;
import org.apache.hertzbeat.common.entity.manager.bulletin.BulletinDto;
import org.apache.hertzbeat.common.entity.manager.bulletin.BulletinMetricsData;
import org.apache.hertzbeat.common.entity.message.CollectRep;
import org.apache.hertzbeat.common.util.JsonUtil;
import org.apache.hertzbeat.manager.service.BulletinService;
import org.apache.hertzbeat.manager.service.MonitorService;
import org.apache.hertzbeat.warehouse.store.realtime.RealTimeDataReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulletin Controller
 */
@Slf4j
@RestController
@RequestMapping(value = "/api/bulletin", produces = {APPLICATION_JSON_VALUE})
public class BulletinController {

    private static final String NO_DATA = "No Data";
    private static final String EMPTY_STRING = "";

    @Autowired
    private BulletinService bulletinService;

    @Autowired
    private RealTimeDataReader realTimeDataReader;

    @Autowired
    private MonitorService monitorService;
    /**
     * add a new bulletin
     */
    @PostMapping
    public ResponseEntity<Message<Void>> addNewBulletin(@Valid @RequestBody BulletinDto bulletinDto) {
        try {
            bulletinService.validate(bulletinDto);
            bulletinService.addBulletin(bulletinDto);
        } catch (Exception e) {
            return ResponseEntity.ok(Message.fail(FAIL_CODE, "Add failed! " + e.getMessage()));
        }
        return ResponseEntity.ok(Message.success("Add success!"));
    }

    /**
     * get All Names
     */
    @Operation(summary = "Get All Bulletin Names", description = "Get All Bulletin Names")
    @GetMapping("/names")
    public ResponseEntity<Message<List<String>>> getAllNames() {
        List<String> names = bulletinService.getAllNames();
        return ResponseEntity.ok(Message.success(names));
    }

    /**
     * delete bulletin by name
     */
    @Operation(summary = "Delete Bulletin by Name", description = "Delete Bulletin by Name")
    @DeleteMapping
    public ResponseEntity<Message<Void>> deleteBulletin(
            @Parameter(description = "Bulletin Name", example = "402372614668544")
            @RequestParam List<String> names) {
        try {
            bulletinService.deleteBulletinByName(names);
        }catch (Exception e) {
            return ResponseEntity.ok(Message.fail(FAIL_CODE, "Delete failed!" + e.getMessage()));
        }
        return ResponseEntity.ok(Message.success("Delete success!"));
    }

    @GetMapping("/metrics")
    @Operation(summary = "Query All Bulletin Real Time Metrics Data", description = "Query All Bulletin real-time metrics data of monitoring indicators")
    public ResponseEntity<Message<?>> getAllMetricsData(
            @RequestParam(name = "name") String name,
            @RequestParam(defaultValue = "0", name = "pageIndex") int pageIndex,
            @RequestParam(defaultValue = "10", name = "pageSize") int pageSize) {
        if (!realTimeDataReader.isServerAvailable()) {
            return ResponseEntity.ok(Message.fail(FAIL_CODE, "real time store not available"));
        }

        Pageable pageable = PageRequest.of(pageIndex, pageSize);
        Bulletin bulletin = bulletinService.getBulletinByName(name);

        BulletinMetricsData.BulletinMetricsDataBuilder contentBuilder = BulletinMetricsData.builder()
                .name(bulletin.getName())
                .column(bulletin.getMetrics());


        BulletinMetricsData data = buildBulletinMetricsData(contentBuilder, bulletin);



//        Page<BulletinMetricsData> metricsDataPage = new PageImpl<>(data, pageable, data.getData().size());

        return ResponseEntity.ok(Message.success(data));
    }

    private BulletinMetricsData buildBulletinMetricsData(BulletinMetricsData.BulletinMetricsDataBuilder contentBuilder, Bulletin bulletin) {
        List<BulletinMetricsData.Data> dataList = new ArrayList<>();
        for (Long monitorId : bulletin.getMonitorIds()) {
            Monitor monitor = monitorService.getMonitor(monitorId);
            BulletinMetricsData.Data.DataBuilder dataBuilder = BulletinMetricsData.Data.builder()
                    .monitorId(monitorId)
                    .monitorName(monitor.getName())
                    .host(monitor.getHost());

            List<BulletinMetricsData.Metric> metrics = new ArrayList<>();
            Map<String, List<String>> fieldMap = JsonUtil.fromJson(bulletin.getFields(), new TypeReference<>() {});

            if (fieldMap != null) {
                // Convert entry set to a list and sort it
                List<Map.Entry<String, List<String>>> entries = new ArrayList<>(fieldMap.entrySet());
                entries.sort(Map.Entry.comparingByKey());

                for (Map.Entry<String, List<String>> entry : entries) {
                    String metric = entry.getKey();
                    List<String> fields = entry.getValue();
                    BulletinMetricsData.Metric.MetricBuilder metricBuilder = BulletinMetricsData.Metric.builder()
                            .name(metric);
                    CollectRep.MetricsData currentMetricsData = realTimeDataReader.getCurrentMetricsData(monitorId, metric);
                    List<List<BulletinMetricsData.Field>> fieldsList = (currentMetricsData != null) ?
                            buildFieldsListFromCurrentData(currentMetricsData) :
                            buildFieldsListNoData(metric, fields);
                    metricBuilder.fields(fieldsList);
                    metrics.add(metricBuilder.build());
                }
            }


            dataBuilder.metrics(metrics);
            dataList.add(dataBuilder.build());
        }

        contentBuilder.content(dataList);
        return contentBuilder.build();
    }




    private List<List<BulletinMetricsData.Field>> buildFieldsListFromCurrentData(CollectRep.MetricsData currentMetricsData) {
        return currentMetricsData.getValuesList().stream()
                .map(valueRow -> {
                    List<BulletinMetricsData.Field> fields = currentMetricsData.getFieldsList().stream()
                            .map(field -> BulletinMetricsData.Field.builder()
                                    .key(field.getName())
                                    .unit(field.getUnit())
                                    .build())
                            .toList();

                    for (int i = 0; i < fields.size(); i++) {
                        fields.get(i).setValue(valueRow.getColumns(i));
                    }
                    return fields;
                })
                .toList();
    }

    private List<List<BulletinMetricsData.Field>> buildFieldsListNoData(String metric, List<String> fields) {
        return Collections.singletonList(fields.stream()
                .map(field -> BulletinMetricsData.Field.builder()
                        .key(field)
                        .unit(EMPTY_STRING)
                        .value(NO_DATA)
                        .build())
                .toList());
    }
}