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
package org.apache.rocketmq.broker.slave;

import java.io.IOException;
import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.broker.subscription.SubscriptionGroupManager;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.protocol.body.ConsumerOffsetSerializeWrapper;
import org.apache.rocketmq.common.protocol.body.SubscriptionGroupWrapper;
import org.apache.rocketmq.common.protocol.body.TopicConfigSerializeWrapper;
import org.apache.rocketmq.store.config.StorePathConfigHelper;

/**
 * 元数据同步类，负责从节点的数据同步
 */
public class SlaveSynchronize {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);
    private final BrokerController brokerController;
    private volatile String masterAddr = null;

    public SlaveSynchronize(BrokerController brokerController) {
        this.brokerController = brokerController;
    }

    public String getMasterAddr() {
        return masterAddr;
    }

    public void setMasterAddr(String masterAddr) {
        this.masterAddr = masterAddr;
    }

    /**
     * 同步的数据，ConfigManager
     */
    public void syncAll() {
        this.syncTopicConfig();  //  同步topic配置信息
        this.syncConsumerOffset(); // 同步消费进度
        this.syncDelayOffset(); // 同步DelayOffset定时任务
        this.syncSubscriptionGroupConfig(); // 同步订阅组配置信息
    }

    private void syncTopicConfig() {
        // 获取master地址
        String masterAddrBak = this.masterAddr;
        if (masterAddrBak != null && !masterAddrBak.equals(brokerController.getBrokerAddr())) {
            try {
                // 调用getAllTopicConfig从master中拉去TopicConfig的配置信息
                TopicConfigSerializeWrapper topicWrapper =
                    this.brokerController.getBrokerOuterAPI().getAllTopicConfig(masterAddrBak);
                // 比较版本号是否一致，如若不一致，进行更新
                if (!this.brokerController.getTopicConfigManager().getDataVersion()
                    .equals(topicWrapper.getDataVersion())) {

                    // 更新TopicConfigManager中的版本信息
                    this.brokerController.getTopicConfigManager().getDataVersion()
                        .assignNewOne(topicWrapper.getDataVersion());
                    // 清空TopicConfigManager中的TopicConfigTable中的信息
                    this.brokerController.getTopicConfigManager().getTopicConfigTable().clear();
                    // 赋值新的信息
                    this.brokerController.getTopicConfigManager().getTopicConfigTable()
                        .putAll(topicWrapper.getTopicConfigTable());
                    // 进行持久化（保存到当前用户的根目录的store目录）
                    this.brokerController.getTopicConfigManager().persist();

                    log.info("Update slave topic config from master, {}", masterAddrBak);
                }
            } catch (Exception e) {
                log.error("SyncTopicConfig Exception, {}", masterAddrBak, e);
            }
        }
    }

    private void syncConsumerOffset() {
        String masterAddrBak = this.masterAddr;
        if (masterAddrBak != null && !masterAddrBak.equals(brokerController.getBrokerAddr())) {
            try {
                ConsumerOffsetSerializeWrapper offsetWrapper =
                    this.brokerController.getBrokerOuterAPI().getAllConsumerOffset(masterAddrBak);
                this.brokerController.getConsumerOffsetManager().getOffsetTable()
                    .putAll(offsetWrapper.getOffsetTable());
                this.brokerController.getConsumerOffsetManager().persist();
                log.info("Update slave consumer offset from master, {}", masterAddrBak);
            } catch (Exception e) {
                log.error("SyncConsumerOffset Exception, {}", masterAddrBak, e);
            }
        }
    }

    private void syncDelayOffset() {
        String masterAddrBak = this.masterAddr;
        if (masterAddrBak != null && !masterAddrBak.equals(brokerController.getBrokerAddr())) {
            try {
                String delayOffset =
                    this.brokerController.getBrokerOuterAPI().getAllDelayOffset(masterAddrBak);
                if (delayOffset != null) {

                    String fileName =
                        StorePathConfigHelper.getDelayOffsetStorePath(this.brokerController
                            .getMessageStoreConfig().getStorePathRootDir());
                    try {
                        MixAll.string2File(delayOffset, fileName);
                    } catch (IOException e) {
                        log.error("Persist file Exception, {}", fileName, e);
                    }
                }
                log.info("Update slave delay offset from master, {}", masterAddrBak);
            } catch (Exception e) {
                log.error("SyncDelayOffset Exception, {}", masterAddrBak, e);
            }
        }
    }

    private void syncSubscriptionGroupConfig() {
        String masterAddrBak = this.masterAddr;
        if (masterAddrBak != null  && !masterAddrBak.equals(brokerController.getBrokerAddr())) {
            try {
                SubscriptionGroupWrapper subscriptionWrapper =
                    this.brokerController.getBrokerOuterAPI()
                        .getAllSubscriptionGroupConfig(masterAddrBak);

                if (!this.brokerController.getSubscriptionGroupManager().getDataVersion()
                    .equals(subscriptionWrapper.getDataVersion())) {
                    SubscriptionGroupManager subscriptionGroupManager =
                        this.brokerController.getSubscriptionGroupManager();
                    subscriptionGroupManager.getDataVersion().assignNewOne(
                        subscriptionWrapper.getDataVersion());
                    subscriptionGroupManager.getSubscriptionGroupTable().clear();
                    subscriptionGroupManager.getSubscriptionGroupTable().putAll(
                        subscriptionWrapper.getSubscriptionGroupTable());
                    subscriptionGroupManager.persist();
                    log.info("Update slave Subscription Group from master, {}", masterAddrBak);
                }
            } catch (Exception e) {
                log.error("SyncSubscriptionGroup Exception, {}", masterAddrBak, e);
            }
        }
    }
}
