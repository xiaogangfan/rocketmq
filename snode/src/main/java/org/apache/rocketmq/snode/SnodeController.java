package org.apache.rocketmq.snode;/*
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

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.protocol.RequestCode;
import org.apache.rocketmq.common.utils.ThreadUtils;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.RemotingClient;
import org.apache.rocketmq.remoting.RemotingClientFactory;
import org.apache.rocketmq.remoting.RemotingServer;
import org.apache.rocketmq.remoting.RemotingServerFactory;
import org.apache.rocketmq.remoting.ClientConfig;
import org.apache.rocketmq.remoting.ServerConfig;
import org.apache.rocketmq.snode.client.ClientHousekeepingService;
import org.apache.rocketmq.snode.client.ConsumerIdsChangeListener;
import org.apache.rocketmq.snode.client.ConsumerManager;
import org.apache.rocketmq.snode.client.DefaultConsumerIdsChangeListener;
import org.apache.rocketmq.snode.client.ProducerManager;
import org.apache.rocketmq.snode.client.SubscriptionGroupManager;
import org.apache.rocketmq.snode.config.SnodeConfig;
import org.apache.rocketmq.snode.interceptor.InterceptorFactory;
import org.apache.rocketmq.snode.interceptor.InterceptorGroup;
import org.apache.rocketmq.snode.interceptor.Interceptor;
import org.apache.rocketmq.snode.offset.ConsumerOffsetManager;
import org.apache.rocketmq.snode.processor.ConsumerManageProcessor;
import org.apache.rocketmq.snode.processor.HearbeatProcessor;
import org.apache.rocketmq.snode.processor.PullMessageProcessor;
import org.apache.rocketmq.snode.processor.SendMessageProcessor;
import org.apache.rocketmq.snode.service.EnodeService;
import org.apache.rocketmq.snode.service.NnodeService;
import org.apache.rocketmq.snode.service.ScheduledService;
import org.apache.rocketmq.snode.service.impl.EnodeServiceImpl;
import org.apache.rocketmq.snode.service.impl.NnodeServiceImpl;
import org.apache.rocketmq.snode.service.impl.ScheduledServiceImpl;

public class SnodeController {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.SNODE_LOGGER_NAME);

    private final SnodeConfig snodeConfig;
    private final ServerConfig nettyServerConfig;
    private final ClientConfig nettyClientConfig;
    private RemotingClient remotingClient;
    private RemotingServer snodeServer;
    private ExecutorService sendMessageExecutor;
    private ExecutorService heartbeatExecutor;
    private ExecutorService pullMessageExecutor;
    private ExecutorService consumerManageExecutor;
    private EnodeService enodeService;
    private NnodeService nnodeService;
    private ExecutorService consumerManagerExecutor;
    private ScheduledService scheduledService;
    private ProducerManager producerManager;
    private ConsumerManager consumerManager;
    private ClientHousekeepingService clientHousekeepingService;
    private SubscriptionGroupManager subscriptionGroupManager;
    private ConsumerOffsetManager consumerOffsetManager;
    private ConsumerManageProcessor consumerManageProcessor;
    private SendMessageProcessor sendMessageProcessor;
    private PullMessageProcessor pullMessageProcessor;
    private HearbeatProcessor hearbeatProcessor;
    private InterceptorGroup consumeMessageInterceptorGroup;
    private InterceptorGroup sendMessageInterceptorGroup;

    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl(
        "SnodeControllerScheduledThread"));

    public SnodeController(ServerConfig nettyServerConfig,
        ClientConfig nettyClientConfig,
        SnodeConfig snodeConfig) {
        this.nettyClientConfig = nettyClientConfig;
        this.nettyServerConfig = nettyServerConfig;
        this.snodeConfig = snodeConfig;
        this.enodeService = new EnodeServiceImpl(this);
        this.nnodeService = new NnodeServiceImpl(this);
        this.scheduledService = new ScheduledServiceImpl(this);
        this.remotingClient = RemotingClientFactory.createInstance().init(this.getNettyClientConfig(), null);

        this.sendMessageExecutor = ThreadUtils.newThreadPoolExecutor(
            snodeConfig.getSnodeSendMessageMinPoolSize(),
            snodeConfig.getSnodeSendMessageMaxPoolSize(),
            3000,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(snodeConfig.getSnodeSendThreadPoolQueueCapacity()),
            "SnodeSendMessageThread",
            false);

        this.pullMessageExecutor = ThreadUtils.newThreadPoolExecutor(
            snodeConfig.getSnodeSendMessageMinPoolSize(),
            snodeConfig.getSnodeSendMessageMaxPoolSize(),
            3000,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(snodeConfig.getSnodeSendThreadPoolQueueCapacity()),
            "SnodePullMessageThread",
            false);

        this.heartbeatExecutor = ThreadUtils.newThreadPoolExecutor(
            snodeConfig.getSnodeHeartBeatCorePoolSize(),
            snodeConfig.getSnodeHeartBeatMaxPoolSize(),
            1000 * 60,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(snodeConfig.getSnodeHeartBeatThreadPoolQueueCapacity()),
            "SnodeHeartbeatThread",
            true);

        this.consumerManagerExecutor = ThreadUtils.newThreadPoolExecutor(
            snodeConfig.getSnodeSendMessageMinPoolSize(),
            snodeConfig.getSnodeSendMessageMaxPoolSize(),
            3000,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(snodeConfig.getSnodeSendThreadPoolQueueCapacity()),
            "SnodePullMessageThread",
            false);

        this.consumerManageExecutor = ThreadUtils.newThreadPoolExecutor(
            snodeConfig.getSnodeSendMessageMinPoolSize(),
            snodeConfig.getSnodeSendMessageMaxPoolSize(),
            3000,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(snodeConfig.getSnodeSendThreadPoolQueueCapacity()),
            "ConsumerManagerThread",
            false);

        if (this.snodeConfig.getNamesrvAddr() != null) {
            this.nnodeService.updateNnodeAddressList(this.snodeConfig.getNamesrvAddr());
            log.info("Set user specified name server address: {}", this.snodeConfig.getNamesrvAddr());
        }

        this.producerManager = new ProducerManager();

        ConsumerIdsChangeListener consumerIdsChangeListener = new DefaultConsumerIdsChangeListener(this);
        this.consumerManager = new ConsumerManager(consumerIdsChangeListener);
        this.subscriptionGroupManager = new SubscriptionGroupManager(this);
        this.clientHousekeepingService = new ClientHousekeepingService(this.producerManager, this.consumerManager);
        this.consumerOffsetManager = new ConsumerOffsetManager(this);
        this.consumerManageProcessor = new ConsumerManageProcessor(this);
        this.sendMessageProcessor = new SendMessageProcessor(this);
        this.hearbeatProcessor = new HearbeatProcessor(this);
        this.pullMessageProcessor = new PullMessageProcessor(this);
    }

    public SnodeConfig getSnodeConfig() {
        return snodeConfig;
    }

    public boolean initialize() {
        this.snodeServer = RemotingServerFactory.createInstance().init(this.nettyServerConfig, this.clientHousekeepingService);
        this.registerProcessor();
        initInterceptorGroup();
        return true;
    }

    private void initInterceptorGroup() {
        List<Interceptor> consumeMessageInterceptors = InterceptorFactory.getInstance().loadConsumeMessageInterceptors();
        if (consumeMessageInterceptors != null) {
            this.consumeMessageInterceptorGroup = new InterceptorGroup();
            for (Interceptor interceptor : consumeMessageInterceptors) {
                this.consumeMessageInterceptorGroup.registerInterceptor(interceptor);
            }
        }
        List<Interceptor> sendMessageInterceptors = InterceptorFactory.getInstance().loadSendMessageInterceptors();
        if (sendMessageInterceptors != null) {
            this.sendMessageInterceptorGroup = new InterceptorGroup();
            for (Interceptor interceptor : sendMessageInterceptors) {
                this.sendMessageInterceptorGroup.registerInterceptor(interceptor);
            }
        }
    }

    public void registerProcessor() {
        this.snodeServer.registerProcessor(RequestCode.SEND_MESSAGE_V2, sendMessageProcessor, this.sendMessageExecutor);
        this.snodeServer.registerProcessor(RequestCode.CONSUMER_SEND_MSG_BACK, sendMessageProcessor, this.sendMessageExecutor);
        this.snodeServer.registerProcessor(RequestCode.HEART_BEAT, hearbeatProcessor, this.heartbeatExecutor);
        this.snodeServer.registerProcessor(RequestCode.UNREGISTER_CLIENT, hearbeatProcessor, this.heartbeatExecutor);
        this.snodeServer.registerProcessor(RequestCode.SNODE_PULL_MESSAGE, pullMessageProcessor, this.pullMessageExecutor);
        this.snodeServer.registerProcessor(RequestCode.GET_CONSUMER_LIST_BY_GROUP, consumerManageProcessor, this.consumerManageExecutor);
        this.snodeServer.registerProcessor(RequestCode.UPDATE_CONSUMER_OFFSET, consumerManageProcessor, this.consumerManageExecutor);
        this.snodeServer.registerProcessor(RequestCode.QUERY_CONSUMER_OFFSET, consumerManageProcessor, this.consumerManageExecutor);
        this.snodeServer.registerProcessor(RequestCode.GET_MIN_OFFSET, consumerManageProcessor, this.consumerManageExecutor);
        this.snodeServer.registerProcessor(RequestCode.GET_MAX_OFFSET, consumerManageProcessor, this.consumerManageExecutor);
        this.snodeServer.registerProcessor(RequestCode.SEARCH_OFFSET_BY_TIMESTAMP, consumerManageProcessor, this.consumerManageExecutor);
    }

    public void start() {
        initialize();
        this.snodeServer.start();
        this.remotingClient.start();
        this.scheduledService.startScheduleTask();
        this.clientHousekeepingService.start(this.snodeConfig.getHouseKeepingInterval());
    }

    public void shutdown() {
        this.sendMessageExecutor.shutdown();
        this.pullMessageExecutor.shutdown();
        this.heartbeatExecutor.shutdown();
        this.consumerManagerExecutor.shutdown();
        this.scheduledExecutorService.shutdown();
        this.remotingClient.shutdown();
        this.scheduledService.shutdown();
        this.clientHousekeepingService.shutdown();
    }

    public ProducerManager getProducerManager() {
        return producerManager;
    }

    public RemotingServer getSnodeServer() {
        return snodeServer;
    }

    public ConsumerManager getConsumerManager() {
        return consumerManager;
    }

    public SubscriptionGroupManager getSubscriptionGroupManager() {
        return subscriptionGroupManager;
    }

    public ClientConfig getNettyClientConfig() {
        return nettyClientConfig;
    }

    public EnodeService getEnodeService() {
        return enodeService;
    }

    public NnodeService getNnodeService() {
        return nnodeService;
    }

    public RemotingClient getRemotingClient() {
        return remotingClient;
    }

    public ConsumerOffsetManager getConsumerOffsetManager() {
        return consumerOffsetManager;
    }

    public InterceptorGroup getConsumeMessageInterceptorGroup() {
        return consumeMessageInterceptorGroup;
    }

    public InterceptorGroup getSendMessageInterceptorGroup() {
        return sendMessageInterceptorGroup;
    }
}