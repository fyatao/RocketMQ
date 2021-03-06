/**
 * $Id$
 */
package com.alibaba.rocketmq.broker.client;

import io.netty.channel.Channel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.remoting.common.RemotingHelper;


/**
 * 管理Producer组及各个Producer连接
 * 
 * @author shijia.wxr<vintage.wang@gmail.com>
 */
public class ProducerManager {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.BrokerLoggerName);
    private static final long LockTimeoutMillis = 3000;

    private static final long ChannelExpiredTimeout = 1000 * 120;

    private final Random random = new Random(System.currentTimeMillis());

    private final Lock hashcodeChannelLock = new ReentrantLock();
    private final HashMap<Integer /* group hash code */, List<ClientChannelInfo>> hashcodeChannelTable =
            new HashMap<Integer, List<ClientChannelInfo>>();

    private final Lock groupChannelLock = new ReentrantLock();
    private final HashMap<String /* group name */, HashMap<Integer/* channel id */, ClientChannelInfo>> groupChannelTable =
            new HashMap<String, HashMap<Integer, ClientChannelInfo>>();


    public ProducerManager() {
    }


    private int generateRandmonNum() {
        int value = this.random.nextInt();

        if (value < 0) {
            value = Math.abs(value);
        }

        return value;
    }


    public ClientChannelInfo pickProducerChannelRandomly(final int producerGroupHashCode) {
        try {
            if (this.hashcodeChannelLock.tryLock(LockTimeoutMillis, TimeUnit.MILLISECONDS)) {
                try {
                    List<ClientChannelInfo> channelInfoList = this.hashcodeChannelTable.get(producerGroupHashCode);
                    if (channelInfoList != null && !channelInfoList.isEmpty()) {
                        int index = this.generateRandmonNum() % channelInfoList.size();
                        ClientChannelInfo info = channelInfoList.get(index);
                        return info;
                    }
                }
                finally {
                    this.hashcodeChannelLock.unlock();
                }
            }
            else {
                log.warn("ProducerManager pickProducerChannelRandomly lock timeout");
            }
        }
        catch (InterruptedException e) {
            log.error("", e);
        }

        return null;
    }


    public void scanNotActiveChannel() {
        try {
            if (this.hashcodeChannelLock.tryLock(LockTimeoutMillis, TimeUnit.MILLISECONDS)) {
                try {
                    for (final Map.Entry<Integer, List<ClientChannelInfo>> entry : this.hashcodeChannelTable
                        .entrySet()) {
                        final Integer groupHashCode = entry.getKey();
                        final List<ClientChannelInfo> clientChannelInfoList = entry.getValue();
                        final List<ClientChannelInfo> willRemoveChannel = new ArrayList<ClientChannelInfo>();
                        for (ClientChannelInfo clientChannelInfo : clientChannelInfoList) {
                            long diff = System.currentTimeMillis() - clientChannelInfo.getLastUpdateTimestamp();
                            if (diff > ChannelExpiredTimeout) {
                                willRemoveChannel.add(clientChannelInfo);
                            }
                        }

                        for (ClientChannelInfo clientChannelInfo : willRemoveChannel) {
                            boolean result = clientChannelInfoList.remove(clientChannelInfo);
                            if (result) {
                                log.warn(
                                    "SCAN: remove expired channel[{}] from ProducerManager hashcodeChannelTable, producer group hash code: {}",
                                    RemotingHelper.parseChannelRemoteAddr(clientChannelInfo.getChannel()),
                                    groupHashCode);
                                clientChannelInfo.getChannel().close();
                            }
                        }
                    }
                }
                finally {
                    this.hashcodeChannelLock.unlock();
                }
            }
            else {
                log.warn("ProducerManager scanNotActiveChannel lock timeout");
            }
        }
        catch (InterruptedException e) {
            log.error("", e);
        }

        try {
            if (this.groupChannelLock.tryLock(LockTimeoutMillis, TimeUnit.MILLISECONDS)) {
                try {
                    for (final Map.Entry<String, HashMap<Integer, ClientChannelInfo>> entry : this.groupChannelTable
                        .entrySet()) {
                        final String group = entry.getKey();
                        final HashMap<Integer, ClientChannelInfo> chlMap = entry.getValue();

                        for (final Map.Entry<Integer, ClientChannelInfo> item : chlMap.entrySet()) {
                            final Integer id = item.getKey();
                            final ClientChannelInfo info = item.getValue();

                            long diff = System.currentTimeMillis() - info.getLastUpdateTimestamp();
                            if (diff > ChannelExpiredTimeout) {
                                ClientChannelInfo old = chlMap.remove(id);
                                if (old != null) {
                                    log.warn(
                                        "SCAN: remove expired channel[{}] from ProducerManager groupChannelTable, producer group name: {}",
                                        RemotingHelper.parseChannelRemoteAddr(info.getChannel()), group);
                                    info.getChannel().close();
                                }
                            }
                        }
                    }
                }
                finally {
                    this.groupChannelLock.unlock();
                }
            }
            else {
                log.warn("ProducerManager scanNotActiveChannel lock timeout");
            }
        }
        catch (InterruptedException e) {
            log.error("", e);
        }
    }


    public void doChannelCloseEvent(final String remoteAddr, final Channel channel) {
        if (channel != null) {
            try {
                if (this.hashcodeChannelLock.tryLock(LockTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    try {
                        for (final Map.Entry<Integer, List<ClientChannelInfo>> entry : this.hashcodeChannelTable
                            .entrySet()) {
                            final Integer groupHashCode = entry.getKey();
                            final List<ClientChannelInfo> clientChannelInfoList = entry.getValue();
                            boolean result = clientChannelInfoList.remove(new ClientChannelInfo(channel));
                            if (result) {
                                log.info(
                                    "NETTY EVENT: remove channel[{}][{}] from ProducerManager hashcodeChannelTable, producer group hash code: {}",
                                    RemotingHelper.parseChannelRemoteAddr(channel), remoteAddr, groupHashCode);
                            }
                        }
                    }
                    finally {
                        this.hashcodeChannelLock.unlock();
                    }
                }
                else {
                    log.warn("ProducerManager doChannelCloseEvent lock timeout");
                }
            }
            catch (InterruptedException e) {
                log.error("", e);
            }

            try {
                if (this.groupChannelLock.tryLock(LockTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    try {
                        for (final Map.Entry<String, HashMap<Integer, ClientChannelInfo>> entry : this.groupChannelTable
                            .entrySet()) {
                            final String group = entry.getKey();
                            final HashMap<Integer, ClientChannelInfo> clientChannelInfoTable = entry.getValue();
                            final ClientChannelInfo clientChannelInfo =
                                    clientChannelInfoTable.remove(channel.id());
                            if (clientChannelInfo != null) {
                                log.info(
                                    "NETTY EVENT: remove channel[{}][{}] from ProducerManager groupChannelTable, producer group: {}",
                                    clientChannelInfo.toString(), remoteAddr, group);
                            }
                        }
                    }
                    finally {
                        this.groupChannelLock.unlock();
                    }
                }
                else {
                    log.warn("ProducerManager doChannelCloseEvent lock timeout");
                }
            }
            catch (InterruptedException e) {
                log.error("", e);
            }
        }
    }


    public void registerProducer(final String group, final ClientChannelInfo clientChannelInfo) {
        try {
            ClientChannelInfo clientChannelInfoFound = null;
            if (this.hashcodeChannelLock.tryLock(LockTimeoutMillis, TimeUnit.MILLISECONDS)) {
                try {
                    List<ClientChannelInfo> clientChannelInfoList =
                            this.hashcodeChannelTable.get(group.hashCode());
                    if (null == clientChannelInfoList) {
                        clientChannelInfoList = new ArrayList<ClientChannelInfo>();
                        this.hashcodeChannelTable.put(group.hashCode(), clientChannelInfoList);
                    }

                    int index = clientChannelInfoList.indexOf(clientChannelInfo);
                    if (index >= 0) {
                        clientChannelInfoFound = clientChannelInfoList.get(index);
                    }

                    if (null == clientChannelInfoFound) {
                        clientChannelInfoList.add(clientChannelInfo);
                    }
                }
                finally {
                    this.hashcodeChannelLock.unlock();
                }

                if (clientChannelInfoFound != null) {
                    clientChannelInfoFound.setLastUpdateTimestamp(System.currentTimeMillis());
                }
            }
            else {
                log.warn("ProducerManager registerProducer lock timeout");
            }
        }
        catch (InterruptedException e) {
            log.error("", e);
        }

        try {
            ClientChannelInfo clientChannelInfoFound = null;

            if (this.groupChannelLock.tryLock(LockTimeoutMillis, TimeUnit.MILLISECONDS)) {
                try {
                    HashMap<Integer, ClientChannelInfo> channelTable = this.groupChannelTable.get(group);
                    if (null == channelTable) {
                        channelTable = new HashMap<Integer, ClientChannelInfo>();
                        this.groupChannelTable.put(group, channelTable);
                    }

                    clientChannelInfoFound = channelTable.get(clientChannelInfo.getChannel().id());
                    if (null == clientChannelInfoFound) {
                        channelTable.put(clientChannelInfo.getChannel().id(), clientChannelInfo);
                        log.info("new producer connected, group: {} channel: {}", group,
                            clientChannelInfo.toString());
                    }
                }
                finally {
                    this.groupChannelLock.unlock();
                }

                if (clientChannelInfoFound != null) {
                    clientChannelInfoFound.setLastUpdateTimestamp(System.currentTimeMillis());
                }
            }
            else {
                log.warn("ProducerManager registerProducer lock timeout");
            }
        }
        catch (InterruptedException e) {
            log.error("", e);
        }
    }


    public void unregisterProducer(final String group, final ClientChannelInfo clientChannelInfo) {
        try {
            if (this.hashcodeChannelLock.tryLock(LockTimeoutMillis, TimeUnit.MILLISECONDS)) {
                try {
                    List<ClientChannelInfo> clientChannelInfoList =
                            this.hashcodeChannelTable.get(group.hashCode());
                    if (null != clientChannelInfoList && !clientChannelInfoList.isEmpty()) {
                        boolean result = clientChannelInfoList.remove(clientChannelInfo);
                        if (result) {
                            log.info("unregister a producer[{}] from hashcodeChannelTable {}", group,
                                clientChannelInfo.toString());
                        }

                        if (clientChannelInfoList.isEmpty()) {
                            this.hashcodeChannelTable.remove(group.hashCode());
                            log.info("unregister a producer group[{}] from hashcodeChannelTable", group);
                        }
                    }
                }
                finally {
                    this.hashcodeChannelLock.unlock();
                }
            }
            else {
                log.warn("ProducerManager unregisterProducer lock timeout");
            }
        }
        catch (InterruptedException e) {
            log.error("", e);
        }

        try {
            if (this.groupChannelLock.tryLock(LockTimeoutMillis, TimeUnit.MILLISECONDS)) {
                try {
                    HashMap<Integer, ClientChannelInfo> channelTable = this.groupChannelTable.get(group);
                    if (null != channelTable && !channelTable.isEmpty()) {
                        ClientChannelInfo old = channelTable.remove(clientChannelInfo.getChannel().id());
                        if (old != null) {
                            log.info("unregister a producer[{}] from groupChannelTable {}", group,
                                clientChannelInfo.toString());
                        }

                        if (channelTable.isEmpty()) {
                            this.hashcodeChannelTable.remove(group.hashCode());
                            log.info("unregister a producer group[{}] from groupChannelTable", group);
                        }
                    }
                }
                finally {
                    this.groupChannelLock.unlock();
                }
            }
            else {
                log.warn("ProducerManager unregisterProducer lock timeout");
            }
        }
        catch (InterruptedException e) {
            log.error("", e);
        }
    }
}
