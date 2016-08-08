package com.ctrip.xpipe.redis.meta.server.cluster.impl;


import java.util.Set;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.springframework.beans.factory.annotation.Autowired;

import com.ctrip.xpipe.api.codec.Codec;
import com.ctrip.xpipe.api.command.CommandFuture;
import com.ctrip.xpipe.api.lifecycle.TopElement;
import com.ctrip.xpipe.command.AbstractCommand;
import com.ctrip.xpipe.redis.core.meta.MetaZkConfig;
import com.ctrip.xpipe.redis.meta.server.cluster.ClusterServerInfo;
import com.ctrip.xpipe.redis.meta.server.cluster.CurrentClusterServer;
import com.ctrip.xpipe.redis.meta.server.cluster.SLOT_STATE;
import com.ctrip.xpipe.redis.meta.server.cluster.SlotInfo;
import com.ctrip.xpipe.redis.meta.server.cluster.SlotManager;
import com.ctrip.xpipe.redis.meta.server.config.MetaServerConfig;
import com.ctrip.xpipe.utils.XpipeThreadFactory;
import com.ctrip.xpipe.zk.ZkClient;

/**
 * @author wenchao.meng
 *
 * Jul 25, 2016
 */
public class DefaultCurrentClusterServer extends AbstractClusterServer implements CurrentClusterServer, TopElement{

	@Autowired
	private ZkClient zkClient;
	
	@Autowired
	private MetaServerConfig config;
	
	@Autowired
	private SlotManager slotManager;
	
	@Autowired
	private MetaserverLeaderElector metaserverLeaderElector;
	
	private int currentServerId;
	
	private String serverPath;
		
	private ExecutorService executors;

	
	public DefaultCurrentClusterServer() {
	}

	@Override
	protected void doInitialize() throws Exception {

		executors = Executors.newCachedThreadPool(XpipeThreadFactory.create("DEFAULT_CURRENT_CLUSTER_SERVER"));
		
		this.currentServerId = config.getMetaServerId();
		serverPath = MetaZkConfig.getMetaServerRegisterPath() + "/" + currentServerId;
		
		setServerId(currentServerId);
		setClusterServerInfo(new ClusterServerInfo(config.getMetaServerIp(), config.getMetaServerPort()));
	}
	


	@Override
	protected void doStart() throws Exception {
		
		CuratorFramework client = zkClient.get();		

		if(client.checkExists().forPath(serverPath) != null){
			
			ClusterServerInfo info = Codec.DEFAULT.decode(client.getData().forPath(serverPath), ClusterServerInfo.class);
			if(!info.equals(getClusterInfo())){
				throw new IllegalStateException("serverId:" + currentServerId + " already exists!");
			}
			deleteServerPath();
			TimeUnit.MILLISECONDS.sleep(50);//make sure server get notification
		}
		
		logger.info("[doStart][createServerPathCreated]{}", serverPath);
		client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(serverPath, Codec.DEFAULT.encodeAsBytes(getClusterInfo()));
	}

	private void deleteServerPath() throws Exception {
		
		logger.info("[deleteServerPath]{}", serverPath);
		CuratorFramework client = zkClient.get();
		client.delete().forPath(serverPath);
		
		
	}

	@Override
	public int getOrder() {
		return 0;
	}

	@Override
	protected void doStop() throws Exception {
	
		deleteServerPath();
	}
	
	public void setZkClient(ZkClient zkClient) {
		this.zkClient = zkClient;
	}

	public void setConfig(MetaServerConfig config) {
		this.config = config;
	}

	@Override
	public void notifySlotChange(int slotId) {
		new SlotRefreshCommand(slotId).execute(executors);
	}
	
	
	@Override
	public CommandFuture<Void> addSlot(int slotId) {
		return new SlotAddCommand(slotId).execute(executors);
	}

	@Override
	public CommandFuture<Void> deleteSlot(int slotId) {
		return new SlotDeleteCommand(slotId).execute(executors);
	}

	@Override
	public CommandFuture<Void> exportSlot(int slotId) {

		return new SlotExportCommand(slotId).execute();
	}

	@Override
	public CommandFuture<Void> importSlot(int slotId) {
		return new SlotImportCommand(slotId).execute(executors);
	}

	@Override
	public Set<Integer> slots() {
		return slotManager.getSlotsByServerId(currentServerId);
	}
	
	@Override
	public boolean isLeader() {
		return metaserverLeaderElector.amILeader();
	}
	
	
	protected boolean isExporting(Object key){
		
		SlotInfo slotInfo = slotManager.getSlotInfoByKey(key);
		if(slotInfo.getSlotState() == SLOT_STATE.MOVING){
			return true;
		}
		return false;
	}
	
	public void doWaitForSlotCommandsFinish() {
		//TODO wait for slot to clean export info
	}

	@Override
	public boolean hasKey(Object key) {
		
		Integer serverId = slotManager.getServerIdByKey(key);
		if(serverId == null){
			return false;
		}
		return serverId == this.getServerId();
	}


	class SlotImportCommand extends AbstractCommand<Void>{
		
		private int slotId;
		public SlotImportCommand(int slotId){
			this.slotId = slotId;
		}

		@Override
		public String getName() {
			return "SlotImport";
		}

		@Override
		protected void doExecute() throws Exception {
			
			slotManager.refresh(slotId);
			SlotInfo slotInfo = slotManager.getSlotInfo(slotId);
			if(slotInfo.getSlotState() == SLOT_STATE.MOVING && slotInfo.getToServerId() == getServerId()){
				logger.info("[doExecute][import({})]{}, {}", currentServerId, slotId, slotInfo);
			}else{
				throw new IllegalStateException("error import " + slotId + "," + slotInfo);
			}
			doSlotImport(slotId);
			future.setSuccess();
		}
		@Override
		protected void doReset() throws InterruptedException, ExecutionException {
			
		}
	}
	
	class SlotExportCommand extends AbstractCommand<Void>{
		
		private int slotId;
		public SlotExportCommand(int slotId){
			this.slotId = slotId;
		}

		@Override
		public String getName() {
			return "SlotExport";
		}

		@Override
		protected void doExecute() throws Exception {
			
			slotManager.refresh(slotId);
			SlotInfo slotInfo = slotManager.getSlotInfo(slotId);
			if(slotInfo.getSlotState() == SLOT_STATE.MOVING && slotInfo.getServerId() == getServerId()){
				logger.info("[doExecute][export({}){}, {}", currentServerId, slotId, slotInfo, getServerId());
			}else{
				throw new IllegalStateException("error export " + slotId + "," + slotInfo);
			}
			doSlotExport(slotId);
			future.setSuccess();
		}
		@Override
		protected void doReset() throws InterruptedException, ExecutionException {
			
		}
	}

	class SlotAddCommand extends AbstractCommand<Void>{
		
		private int slotId;
		public SlotAddCommand(int slotId) {
			this.slotId = slotId;
		}

		@Override
		public String getName() {
			return "SlotRefreshCommand";
		}

		@Override
		protected void doExecute() throws Exception {
			
			slotManager.refresh(slotId);
			SlotInfo slotInfo = slotManager.getSlotInfo(slotId);
			if(slotInfo.getSlotState() == SLOT_STATE.NORMAL && slotInfo.getServerId() == getServerId()){
				logger.info("[doExecute][slot add]{}, {}", slotId, slotInfo);
			}else{
				throw new IllegalStateException("error add " + slotId + "," + slotInfo);
			}
			doSlotAdd(slotId);
			future.setSuccess();
		}

		@Override
		protected void doReset() throws InterruptedException, ExecutionException {
		}
	} 

	class SlotDeleteCommand extends AbstractCommand<Void>{
		
		private int slotId;
		public SlotDeleteCommand(int slotId) {
			this.slotId = slotId;
		}

		@Override
		public String getName() {
			return "SlotRefreshCommand";
		}

		@Override
		protected void doExecute() throws Exception {
			
			slotManager.refresh(slotId);
			SlotInfo slotInfo = slotManager.getSlotInfo(slotId);
			if(slotInfo.getSlotState() == SLOT_STATE.NORMAL && slotInfo.getServerId() != getServerId()){
				logger.info("[doExecute][slot delete]{}, {}", slotId, slotInfo);
			}else{
				throw new IllegalStateException("error delete " + slotId + "," + slotInfo);
			}
			doSlotDelete(slotId);
			future.setSuccess();
		}
		@Override
		protected void doReset() throws InterruptedException, ExecutionException {
		}
	} 

	class SlotRefreshCommand extends AbstractCommand<Void>{
		
		private int slotId;
		public SlotRefreshCommand(int slotId) {
			this.slotId = slotId;
		}

		@Override
		public String getName() {
			return "SlotRefreshCommand";
		}

		@Override
		protected void doExecute() throws Exception {
			
			slotManager.refresh(slotId);
			future.setSuccess();
		}
		@Override
		protected void doReset() throws InterruptedException, ExecutionException {
		}
	}

	protected void doSlotImport(int slotId) {
	}

	protected void doSlotAdd(int slotId) {
	}

	protected  void doSlotExport(int slotId) {
	} 

	protected void doSlotDelete(int slotId) {
	}


}
