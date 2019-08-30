package com.sohu.tv.mq.cloud.web.controller;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.validation.Valid;

import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.admin.ConsumeStats;
import org.apache.rocketmq.common.admin.OffsetWrapper;
import org.apache.rocketmq.common.admin.TopicOffset;
import org.apache.rocketmq.common.admin.TopicStatsTable;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.protocol.body.ConsumerRunningInfo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

import com.sohu.tv.mq.cloud.bo.Audit;
import com.sohu.tv.mq.cloud.bo.Audit.TypeEnum;
import com.sohu.tv.mq.cloud.bo.AuditAssociateConsumer;
import com.sohu.tv.mq.cloud.bo.AuditConsumer;
import com.sohu.tv.mq.cloud.bo.AuditResetOffset;
import com.sohu.tv.mq.cloud.bo.Cluster;
import com.sohu.tv.mq.cloud.bo.ConsumeStatsExt;
import com.sohu.tv.mq.cloud.bo.Consumer;
import com.sohu.tv.mq.cloud.bo.Topic;
import com.sohu.tv.mq.cloud.bo.TopicTopology;
import com.sohu.tv.mq.cloud.bo.User;
import com.sohu.tv.mq.cloud.bo.UserConsumer;
import com.sohu.tv.mq.cloud.service.AlertService;
import com.sohu.tv.mq.cloud.service.AuditService;
import com.sohu.tv.mq.cloud.service.ClusterService;
import com.sohu.tv.mq.cloud.service.ConsumerService;
import com.sohu.tv.mq.cloud.service.TopicService;
import com.sohu.tv.mq.cloud.service.UserConsumerService;
import com.sohu.tv.mq.cloud.service.UserService;
import com.sohu.tv.mq.cloud.service.VerifyDataService;
import com.sohu.tv.mq.cloud.util.DateUtil;
import com.sohu.tv.mq.cloud.util.FreemarkerUtil;
import com.sohu.tv.mq.cloud.util.Result;
import com.sohu.tv.mq.cloud.util.Status;
import com.sohu.tv.mq.cloud.web.controller.param.AssociateConsumerParam;
import com.sohu.tv.mq.cloud.web.controller.param.ConsumerParam;
import com.sohu.tv.mq.cloud.web.controller.param.UserConsumerParam;
import com.sohu.tv.mq.cloud.web.vo.ConsumerProgressVO;
import com.sohu.tv.mq.cloud.web.vo.QueueOwnerVO;
import com.sohu.tv.mq.cloud.web.vo.UserInfo;
/**
 * 消费者接口
 * @Description: 
 * @author yongfeigao
 * @date 2018年6月12日
 */
@Controller
@RequestMapping("/consumer")
public class ConsumerController extends ViewController {
    
    @Autowired
    private ConsumerService consumerService;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private UserConsumerService userConsumerService;
    
    @Autowired
    private AuditService auditService;
    
    @Autowired
    private AlertService alertService;
    
    @Autowired
    private TopicService topicService;
    
    @Autowired
    private VerifyDataService verifyDataService;
    
    @Autowired
    private ClusterService clusterService;
    
    /**
     * 消费进度
     * @param topicParam
     * @return
     * @throws Exception
     */
    @RequestMapping("/progress")
    public String consumeProgress(UserInfo userInfo, @RequestParam("tid") int tid, Map<String, Object> map) throws Exception {
        String view = viewModule() + "/progress";
        // 获取消费者
        Result<TopicTopology> topicTopologyResult = userService.queryTopicTopology(userInfo.getUser(), tid);
        if(topicTopologyResult.isNotOK()) {
            setResult(map, topicTopologyResult);
            return view;
        }
        // 查询消费进度
        TopicTopology topicTopology = topicTopologyResult.getResult();
        // 拆分不同方式的消费者
        List<Consumer> clusteringConsumerList = new ArrayList<Consumer>();
        List<Consumer> broadcastConsumerList = new ArrayList<Consumer>();
        List<Long> cidList = new ArrayList<Long>();
        for(Consumer consumer : topicTopology.getConsumerList()) {
            cidList.add(consumer.getId());
            if(consumer.isClustering()) {
                clusteringConsumerList.add(consumer);
            } else {
                broadcastConsumerList.add(consumer);
            }
        }
        
        // 获取消费者归属者
        Map<Long, List<User>> consumerMap = getConsumerMap(tid, cidList);
        
        List<ConsumerProgressVO> list = new ArrayList<ConsumerProgressVO>();
        Topic topic = topicTopology.getTopic();
        if(!clusteringConsumerList.isEmpty()) {
            // 抓取集群消费模式下消费者状态
            Cluster cluster = clusterService.getMQClusterById(topic.getClusterId());
            Map<Long, ConsumeStats> consumeStatsMap = consumerService.fetchClusteringConsumeProgress(cluster, 
                    clusteringConsumerList);
            
            // 组装集群模式vo
            for(Consumer consumer : clusteringConsumerList) {
                ConsumerProgressVO consumerProgressVO = new ConsumerProgressVO();
                consumerProgressVO.setConsumer(consumer);
                consumerProgressVO.setTopic(topic.getName());
                consumerProgressVO.setOwnerList(consumerMap.get(consumer.getId()));
                if(consumeStatsMap == null) {
                    list.add(consumerProgressVO);
                    continue;
                }
                ConsumeStats consumeStats = consumeStatsMap.get(consumer.getId());
                if(consumeStats == null) {
                    list.add(consumerProgressVO);
                    continue;
                }
                consumerProgressVO.setConsumeTps(consumeStats.getConsumeTps());
                
                // 拆分正常队列和重试队列
                HashMap<MessageQueue, OffsetWrapper> offsetTable = consumeStats.getOffsetTable();
                Map<MessageQueue, OffsetWrapper> offsetMap = new TreeMap<MessageQueue, OffsetWrapper>();
                Map<MessageQueue, OffsetWrapper> retryOffsetMap = new TreeMap<MessageQueue, OffsetWrapper>();
                for(MessageQueue mq : offsetTable.keySet()) {
                    if(mq.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                        retryOffsetMap.put(mq, offsetTable.get(mq));
                        // 设置topic名字
                        if(consumerProgressVO.getRetryTopic() == null) {
                            consumerProgressVO.setRetryTopic(mq.getTopic());
                        } else if(!mq.getTopic().equals(consumerProgressVO.getRetryTopic())){
                            logger.error("retry consumer:{} has two diffrent topic, {} != {}", consumer.getName(), 
                                    mq.getTopic(), consumerProgressVO.getRetryTopic());
                        }
                    } else {
                        offsetMap.put(mq, offsetTable.get(mq));
                        if(consumerProgressVO.getTopic() == null) {
                            consumerProgressVO.setTopic(mq.getTopic());
                        } else if(!mq.getTopic().equals(consumerProgressVO.getTopic())){
                            logger.error("consumer:{} has two diffrent topic, {} != {}", consumer.getName(), 
                                    mq.getTopic(), consumerProgressVO.getTopic());
                        }
                    }
                }
                // 获取死topic状况
                String dlqTopic = MixAll.getDLQTopic(consumer.getName());
                consumerProgressVO.setDlqTopic(dlqTopic);
                TopicStatsTable topicStatsTable = topicService.stats(cluster, dlqTopic);
                if(topicStatsTable != null) {
                    consumerProgressVO.setDlqOffsetMap(new TreeMap<MessageQueue, TopicOffset>(topicStatsTable.getOffsetTable()));
                }
                
                consumerProgressVO.setOffsetMap(offsetMap);
                consumerProgressVO.setRetryOffsetMap(retryOffsetMap);
                consumerProgressVO.computeTotalDiff();
                list.add(consumerProgressVO);
            }
        }
        setResult(map, list);
        
        List<ConsumerProgressVO> listExt = new ArrayList<ConsumerProgressVO>();
        if(!broadcastConsumerList.isEmpty()) {
            // 抓取广播消费模式下消费者状态
            Map<Long, List<ConsumeStatsExt>> consumeStatsMap = consumerService.fetchBroadcastConsumeProgress(
                    topic, broadcastConsumerList);
            // 组装广播消费模式vo
            for(Consumer consumer : broadcastConsumerList) {
                ConsumerProgressVO consumerProgressVO = new ConsumerProgressVO();
                consumerProgressVO.setConsumer(consumer);
                consumerProgressVO.setTopic(topic.getName());
                consumerProgressVO.setOwnerList(consumerMap.get(consumer.getId()));
                if(consumeStatsMap == null) {
                    listExt.add(consumerProgressVO);
                    continue;
                }
                List<ConsumeStatsExt> consumeStatsList = consumeStatsMap.get(consumer.getId());
                if(consumeStatsList == null) {
                    listExt.add(consumerProgressVO);
                    continue;
                }
                Map<MessageQueue, OffsetWrapper> offsetMap = new TreeMap<MessageQueue, OffsetWrapper>();
                for(ConsumeStatsExt consumeStats : consumeStatsList) {
                    consumerProgressVO.setConsumeTps(consumerProgressVO.getConsumeTps() + consumeStats.getConsumeTps());
                    Map<MessageQueue, OffsetWrapper> offsetTable = consumeStats.getOffsetTable();
                    for(MessageQueue mq : offsetTable.keySet()) {
                        OffsetWrapper prev = offsetMap.get(mq);
                        if(prev == null) {
                            prev = new OffsetWrapper();
                            offsetMap.put(mq, prev);
                        }
                        OffsetWrapper cur = offsetTable.get(mq);
                        prev.setBrokerOffset(prev.getBrokerOffset() + cur.getBrokerOffset());
                        prev.setConsumerOffset(prev.getConsumerOffset() + cur.getConsumerOffset());
                    }
                }
                consumerProgressVO.setOffsetMap(offsetMap);
                consumerProgressVO.setConsumeStatsList(consumeStatsList);
                consumerProgressVO.computeTotalDiff();
                listExt.add(consumerProgressVO);
            }
        }
        setResult(map, "resultExt", Result.getResult(listExt));
        setResult(map, "topic", topic);
        FreemarkerUtil.set("long", Long.class, map);
        return view;
    }
    
    /**
     * 获取消费者map
     * @param tid
     * @param cidList
     * @return
     */
    private Map<Long, List<User>> getConsumerMap(long tid, List<Long> cidList) {
        Map<Long, List<User>> consumerMap = new HashMap<Long, List<User>>();
        if(cidList == null || cidList.size() == 0) {
            return consumerMap;
        }
        Result<List<UserConsumer>> ucListResult = userConsumerService.queryTopicConsumer(tid, cidList);
        if(ucListResult.isEmpty()) {
            return consumerMap;
        }
        Set<Long> uidList = new HashSet<Long>();
        for(UserConsumer userConsumer : ucListResult.getResult()) {
            uidList.add(userConsumer.getUid());
        }
        Result<List<User>> userListResult = userService.query(uidList);
        if(userListResult.isEmpty()) {
            return consumerMap;
        }
        for(UserConsumer userConsumer : ucListResult.getResult()) {
            for(User user : userListResult.getResult()) {
                if(userConsumer.getUid() == user.getId()) {
                    List<User> userList = consumerMap.get(userConsumer.getConsumerId());
                    if(userList == null) {
                        userList = new ArrayList<User>();
                        consumerMap.put(userConsumer.getConsumerId(), userList);
                    }
                    userList.add(user);
                }
            }
        }
        return consumerMap;
    }

    /**
     * 重置偏移量
     * @param userConsumerParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/reset/offset")
    public Result<?> resetOffset(UserInfo userInfo, @Valid UserConsumerParam userConsumerParam) throws Exception {
        // 校验用户是否能删除，防止调用接口误删
        Result<List<UserConsumer>> userConsumerListResult = userConsumerService.queryUserConsumer(userInfo.getUser(),
                userConsumerParam.getTid(), userConsumerParam.getConsumerId());
        if(userConsumerListResult.isNotOK() && !userInfo.getUser().isAdmin()) {
            return userConsumerListResult;
        }
        List<UserConsumer> list = userConsumerListResult.getResult();
        if(list.size() != 1 && !userInfo.getUser().isAdmin()) {
            logger.warn("not unique result, param:{}, result size:{}", userConsumerParam, list.size());
            return Result.getResult(Status.NO_RESULT);
        }
        // 非线上集群，免审
        Cluster cluster = clusterService.getMQClusterById(userConsumerParam.getCid());
        if (cluster != null && !cluster.online()) {
            // 查询consumer信息
            Result<Consumer> consumerResult = consumerService.queryById(userConsumerParam.getConsumerId());
            if (consumerResult.isNotOK()) {
                return consumerResult;
            }
            // 查询topic记录
            Result<Topic> topicResult = topicService.queryTopic(userConsumerParam.getTid());
            if (topicResult.isNotOK()) {
                return topicResult;
            }
             Result<?> resetOffsetResult = consumerService.resetOffset(userConsumerParam.getCid(), topicResult.getResult().getName(),
                     consumerResult.getResult().getName(), userConsumerParam.getOffset());
             // 操作成功，自定义返回文案
             if (resetOffsetResult.isOK()) {
                 return resetOffsetResult.setMessage("操作成功！");
             }
             return resetOffsetResult;
        }
        Date resetOffsetTo = null;
        try {
            if(userConsumerParam.getOffset() != null) {
                resetOffsetTo = DateUtil.getFormat(DateUtil.YMD_DASH_BLANK_HMS_COLON).parse(userConsumerParam.getOffset());
            }
        } catch (Exception e) {
            logger.error("resetOffsetTo param err:{}", userConsumerParam.getOffset(), e);
            return Result.getResult(Status.PARAM_ERROR);
        }
        
        // 构造审核记录
        Audit audit = new Audit();
        // 重新定义操作成功返回的文案
        String message = "";
        if(resetOffsetTo == null) {
            audit.setType(TypeEnum.RESET_OFFSET_TO_MAX.getType());
            message = "跳过堆积申请成功！请耐心等待！";
        } else {
            audit.setType(TypeEnum.RESET_OFFSET.getType());
            message = "消息回溯申请成功！请耐心等待！";
        }
        audit.setUid(userInfo.getUser().getId());
        // 构造重置对象
        AuditResetOffset auditResetOffset = new AuditResetOffset();
        BeanUtils.copyProperties(userConsumerParam, auditResetOffset);
        // 保存记录
        Result<?> result = auditService.saveAuditAndSkipAccumulation(audit, auditResetOffset);
        if(result.isOK()) {
            String tip = getTopicConsumerTip(userConsumerParam.getTid(), userConsumerParam.getConsumerId());
            alertService.sendAuditMail(userInfo.getUser(), TypeEnum.getEnumByType(audit.getType()), tip);
            // 重新定义返回文案
            result.setMessage(message);
        }
        return Result.getWebResult(result);
    }
    
    /**
     * 删除消费者
     * 
     * @param topicParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public Result<?> delete(UserInfo userInfo, @Valid UserConsumerParam userConsumerParam) throws Exception {
        Result<?> isExist = verifyDataService.verifyDeleteConsumerIsExist(userConsumerParam);
        if (isExist.getStatus() != Status.OK.getKey()) {
            return isExist;
        }
        // 校验用户是否能删除，防止调用接口误删
        Result<List<UserConsumer>> userConsumerListResult = userConsumerService.queryUserConsumer(userInfo.getUser(),
                userConsumerParam.getTid(), userConsumerParam.getConsumerId());
        if (userConsumerListResult.isNotOK()) {
            return userConsumerListResult;
        }
        List<UserConsumer> list = userConsumerListResult.getResult();
        //管理员可删
        if(list.size() != 1 && !userInfo.getUser().isAdmin()) {
            logger.warn("not unique result, param:{}, result size:{}", userConsumerParam, list.size());
            return Result.getResult(Status.PERMISSION_DENIED_ERROR);
        }
        
        Result<Consumer> consumerResult = consumerService.queryById(userConsumerParam.getConsumerId());
        if(consumerResult.isNotOK()) {
            return Result.getWebResult(consumerResult);
        }
        
        Result<Topic> topicResult = topicService.queryTopic(consumerResult.getResult().getTid());
        if(topicResult.isNotOK()) {
            return Result.getWebResult(topicResult);
        }
        
        // 构造审核记录
        Audit audit = new Audit();
        audit.setType(TypeEnum.DELETE_CONSUMER.getType());
        audit.setUid(userInfo.getUser().getId());
        // 保存记录
        Result<?> result = auditService.saveAuditAndConsumerDelete(audit, userConsumerParam.getConsumerId(), 
                consumerResult.getResult().getName(), topicResult.getResult().getName());
        if(result.isOK()) {
            alertService.sendAuditMail(userInfo.getUser(), TypeEnum.DELETE_CONSUMER, consumerResult.getResult().getName());
        }
        return Result.getWebResult(result);
    }
    
    
    /**
     * 消费者列表
     * @param topicParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/list")
    public Result<?> list(UserInfo userInfo, @RequestParam("tid") int tid) throws Exception {
        Result<List<Consumer>> consumerListResult = consumerService.queryByTid(tid);
        return Result.getWebResult(consumerListResult);
    }
    
    /**
     * 消费者列表
     * @param topicParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/list/all")
    public Result<?> listAll(UserInfo userInfo) throws Exception {
        Result<List<Consumer>> consumerListResult = consumerService.queryAll();
        return Result.getWebResult(consumerListResult);
    }
    
    /**
     * 关联消费者
     * 
     * @param topicParam
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/associate", method = RequestMethod.POST)
    public Result<?> associate(UserInfo userInfo, @Valid AssociateConsumerParam associateConsumerParam)
            throws Exception {
        return associateUserConsumer(userInfo, userInfo.getUser().getId(), associateConsumerParam.getTid(),
                associateConsumerParam.getCid());
    }
    
    /**
     * 授权关联
     * 
     * @param userInfo
     * @param tid
     * @param uid
     * @param cid
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/auth/associate", method = RequestMethod.POST)
    public Result<?> authAssociate(UserInfo userInfo, @RequestParam("tid") long tid,
            @RequestParam("uid") long uid,
            @RequestParam("cid") long cid) throws Exception {
        if (tid < 1 || uid < 1 || cid < 1) {
            return Result.getResult(Status.PARAM_ERROR);
        }
        return associateUserConsumer(userInfo, uid, tid, cid);
    }
    
    /**
     * 复用之前的逻辑
     * 
     * @param userInfo
     * @param uid
     * @param tid
     * @param cid
     * @return
     */
    private Result<?> associateUserConsumer(UserInfo userInfo, long uid, long tid, long cid) {
        // 验证用户是否已经关联过此消费者
        Result<?> isExist = verifyDataService.verifyUserConsumerIsExist(uid, cid);
        if (isExist.getStatus() != Status.OK.getKey()) {
            return isExist;
        }
        AuditAssociateConsumer auditAssociateConsumer = new AuditAssociateConsumer();
        auditAssociateConsumer.setCid(cid);
        auditAssociateConsumer.setTid(tid);
        auditAssociateConsumer.setUid(uid);
        // 构建Audit
        Audit audit = new Audit();
        audit.setType(Audit.TypeEnum.ASSOCIATE_CONSUMER.getType());
        audit.setStatus(Audit.StatusEnum.INIT.getStatus());
        audit.setUid(userInfo.getUser().getId());
        Result<Audit> result = auditService.saveAuditAndAssociateConsumer(audit, auditAssociateConsumer);
        if (result.isOK()) {
            String tip = getTopicConsumerTip(tid, cid);
            if (uid != userInfo.getUser().getId()) {
                Result<User> userResult = userService.query(uid);
                if (userResult.isNotOK()) {
                    return Result.getResult(Status.EMAIL_SEND_ERR);
                }
                tip = tip + " user:<b>" + userResult.getResult().notBlankName() + "</b>";
            }
            alertService.sendAuditMail(userInfo.getUser(), TypeEnum.ASSOCIATE_CONSUMER, tip);
        }
        return Result.getWebResult(result);
    }

    /**
     * 新建消费者
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value="/add", method=RequestMethod.POST)
    public Result<?> add(UserInfo userInfo, @Valid ConsumerParam consumerParam) throws Exception {
        logger.info("create consumer, user:{} consumerParam:{}", userInfo, consumerParam);
        Result<?> isExist = verifyDataService.verifyAddConsumerIsExist(userInfo.getUser().getId(), consumerParam.getConsumer());
        if (isExist.isNotOK()) {
            return isExist;
        }
        // 构建审核记录
        Audit audit = new Audit();
        audit.setType(Audit.TypeEnum.NEW_CONSUMER.getType());
        audit.setStatus(Audit.StatusEnum.INIT.getStatus());
        audit.setUid(userInfo.getUser().getId());
        audit.setInfo(consumerParam.getInfo());
        
        // 构建消费者审核记录
        AuditConsumer auditConsumer = new AuditConsumer();
        BeanUtils.copyProperties(consumerParam, auditConsumer);
        // 保存记录
        Result<?> result = auditService.saveAuditAndConsumer(audit, auditConsumer);
        if(result.isOK()) {
            String topicTip = getTopicTip(consumerParam.getTid());
            alertService.sendAuditMail(userInfo.getUser(), TypeEnum.NEW_CONSUMER,
                    topicTip + " consumer:<b>" + consumerParam.getConsumer()+"</b>");
        }
        return Result.getWebResult(result);
    }
    
    /**
     * 根据broker查询队列信息
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/broker/queue", method = RequestMethod.GET)
    public Result<?> getQueueList(@RequestParam("topic") String topic,
            @RequestParam("brokerName") String brokerName, 
            @RequestParam("clusterId") long clusterId) throws Exception {
        if (topic == "" || brokerName == "") {
            return Result.getResult(Status.PARAM_ERROR);
        }
        Cluster cluster = clusterService.getMQClusterById(clusterId);
        if (cluster == null) {
            return Result.getResult(cluster);
        }
        TopicStatsTable topicStatsTable = topicService.stats(cluster, topic);
        if (topicStatsTable == null) {
            return Result.getResult(topicStatsTable);
        }
        Map<Integer, Long> queueOffsetMap = new TreeMap<Integer, Long>();
        for (MessageQueue mq : topicStatsTable.getOffsetTable().keySet()) {
            if (mq.getBrokerName().equals(brokerName)) {
                long maxOffset = topicStatsTable.getOffsetTable().get(mq).getMaxOffset();
                if (!queueOffsetMap.containsKey(mq.getQueueId()) || queueOffsetMap.get(mq.getQueueId()) < maxOffset) {
                    queueOffsetMap.put(mq.getQueueId(), maxOffset);
                }
            } 
        }
        return Result.getResult(queueOffsetMap);
    }
    
    /**
     * 获取topic和consumer的提示信息
     * @param tid
     * @param cid
     * @return
     */
    private String getTopicConsumerTip(long tid, long cid) {
        StringBuilder sb = new StringBuilder();
        Result<Topic> topicResult = topicService.queryTopic(tid);
        if(topicResult.isOK()) {
            sb.append(" topic:<b>");
            sb.append(topicResult.getResult().getName());
            sb.append("</b>");
        }
        Result<Consumer> consumerResult = consumerService.queryById(cid);
        if(consumerResult.isOK()) {
            sb.append(" consumer:<b>");
            sb.append(consumerResult.getResult().getName());
            sb.append("</b>");
        }
        return sb.toString();
    }
    
    /**
     * 获取topic的提示信息
     * @param tid
     * @return
     */
    private String getTopicTip(long tid) {
        StringBuilder sb = new StringBuilder();
        Result<Topic> topicResult = topicService.queryTopic(tid);
        if(topicResult.isOK()) {
            sb.append(" topic:<b>");
            sb.append(topicResult.getResult().getName());
            sb.append("</b>");
        }
        return sb.toString();
    }
    
    /**
     * 获取queue的拥有者
     * @param userInfo
     * @param cid
     * @param consumer
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/queue/owner")
    public Result<?> queueRoute(UserInfo userInfo, @RequestParam("cid") int cid,
            @RequestParam("consumer") String consumer) throws Exception {
        Cluster cluster = clusterService.getMQClusterById(cid);
        // 获取消费者运行时信息
        Map<String, ConsumerRunningInfo> map = consumerService.getConsumerRunningInfo(cluster, consumer);
        if(map == null) {
            return Result.getResult(Status.NO_RESULT);
        }
        // 组装vo
        List<QueueOwnerVO> queueConsumerVOList = new ArrayList<QueueOwnerVO>();
        for(String clientId : map.keySet()) {
            String ip = clientId.substring(0, clientId.indexOf("@"));
            ConsumerRunningInfo consumerRunningInfo = map.get(clientId);
            for(MessageQueue messageQueue : consumerRunningInfo.getMqTable().keySet()) {
                if(messageQueue.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                    continue;
                }
                if(messageQueue.getTopic().startsWith(MixAll.DLQ_GROUP_TOPIC_PREFIX)) {
                    continue;
                }
                QueueOwnerVO queueOwnerVO = new QueueOwnerVO();
                queueOwnerVO.setBrokerName(messageQueue.getBrokerName());
                queueOwnerVO.setClientId(ip);
                queueOwnerVO.setQueueId(messageQueue.getQueueId());
                queueConsumerVOList.add(queueOwnerVO);
            }
        }
        return Result.getResult(queueConsumerVOList);
    }
    
    /**
     * 更新描述
     * 
     * @return
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping(value = "/update/info", method = RequestMethod.POST)
    public Result<?> updateInfo(UserInfo userInfo, @RequestParam("cid") int cid,
            @RequestParam("info") String info) throws Exception {
        // 校验当前用户是否拥有权限
        UserConsumer userConsumer = new UserConsumer();
        userConsumer.setConsumerId(cid);
        userConsumer.setUid(userInfo.getUser().getId());
        Result<List<UserConsumer>> ucListResult = userConsumerService.queryUserConsumer(userConsumer);
        if (ucListResult.isEmpty() && !userInfo.getUser().isAdmin()) {
            return Result.getResult(Status.NOT_ALLOWED);
        }
        if (StringUtils.isBlank(info)) {
            return Result.getResult(Status.PARAM_ERROR);
        }
        Result<Integer> result = consumerService.updateConsumerInfo(cid, HtmlUtils.htmlEscape(info.trim(), "UTF-8"));
        logger.info(userInfo.getUser().getName() + " update consumer info , cid:{}, info:{}, status:{}", cid, info, result.isOK());
        return Result.getWebResult(result);
    }
    
    @Override
    public String viewModule() {
        return "consumer";
    }
}
