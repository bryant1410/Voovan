package org.voovan.network.aio;

import org.voovan.Global;
import org.voovan.network.ConnectModel;
import org.voovan.network.EventTrigger;
import org.voovan.network.SocketContext;
import org.voovan.network.exception.ReadMessageException;
import org.voovan.network.exception.SendMessageException;
import org.voovan.network.handler.SynchronousHandler;
import org.voovan.network.messagesplitter.TrasnferSplitter;
import org.voovan.tools.TByteBuffer;
import org.voovan.tools.TEnv;
import org.voovan.tools.log.Logger;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * AioSocket 连接
 * 
 * @author helyho
 *
 * Voovan Framework.
 * WebSite: https://github.com/helyho/Voovan
 * Licence: Apache v2 License
 */
public class AioSocket extends SocketContext {

	private AsynchronousSocketChannel	socketChannel;
	private AioSession					session;
	private ReadCompletionHandler		readCompletionHandler;
	private ByteBuffer readByteBuffer;

	/**
	 * 构造函数
	 * 
	 * @param host   主机地址
	 * @param port   主机端口
	 * @param readTimeout 主机超时时间 (milliseconds)
	 * @throws IOException IO 异常
	 */
	public AioSocket(String host, int port, int readTimeout) throws IOException {
		super(host, port, readTimeout);
		AsynchronousChannelGroup asynchronousChannelGroup = AsynchronousChannelGroup.withThreadPool(Global.getThreadPool());
		this.socketChannel = AsynchronousSocketChannel.open(asynchronousChannelGroup);
		session = new AioSession(this);

		readCompletionHandler = new ReadCompletionHandler(this,  session.getByteBufferChannel());
		this.handler = new SynchronousHandler();
		connectModel = ConnectModel.CLIENT;
	}

	/**
	 * 构造函数
	 *
	 * @param parentSocketContext 父异步 socket 通道
	 * @param socketChannel 异步 socket 通道
	 *
	 * @throws IOException IO 异常
	 */
	protected AioSocket(SocketContext parentSocketContext, AsynchronousSocketChannel socketChannel) throws IOException {
		this.socketChannel = socketChannel;
		this.copyFrom(parentSocketContext);
		session = new AioSession(this);

		readCompletionHandler = new ReadCompletionHandler(this, session.getByteBufferChannel());
		connectModel = ConnectModel.SERVER;
	}

	/**
	 * 捕获 Aio Connect
	 * @throws IOException  IO 异常
	 */
	protected void catchConnected() throws IOException {
		InetSocketAddress socketAddress = new InetSocketAddress(this.host, this.port);
		Future result =  socketChannel.connect(socketAddress);
		try {
			result.get();
		} catch (InterruptedException e) {
			this.close();
			Logger.error(e);
		} catch (ExecutionException e) {
			this.close();
			Throwable causeException = e.getCause();
			if(causeException!=null && causeException instanceof IOException){
				throw (IOException) causeException;
			}
			Logger.error(e);
		}
	}

	/**
	 * 捕获 Aio Read
	 * @param buffer 缓冲区
	 */
	protected void catchRead(ByteBuffer buffer) {
		if(socketChannel.isOpen()) {
			socketChannel.read(buffer, readTimeout, TimeUnit.MILLISECONDS, buffer, readCompletionHandler);
		}
	}

	/**
	 * 获取 Session 对象
	 * @return  Session 对象
     */
	public AioSession getSession() {
		return session;
	}

	/**
	 * 初始化 SSL 环境
	 * @throws SSLException SSL 异常
     */
	private void initSSL() throws SSLException{
		if (connectModel == ConnectModel.SERVER && sslManager != null) {
			sslManager.createServerSSLParser(session);
		} else if (connectModel == ConnectModel.CLIENT && sslManager != null) {
			sslManager.createClientSSLParser(session);
		}
	}
	
	@Override
	public void start() throws IOException{

		syncStart();

		//如果是ServerSocket的 AioSocket 不需要阻塞等待进程
		if(connectModel == ConnectModel.CLIENT ){
            // 等待ServerSocketChannel关闭,结束进程
            while (isConnected()) {
                TEnv.sleep(1);
            }
		}
	}

	/**
	 * 启动同步的上下文连接,同步读写时使用
	 *
	 * @exception IOException IO异常
	 */
	public void syncStart() throws IOException {

		initSSL();

		//如果没有消息分割器默认使用透传分割器
		if(messageSplitter == null){
			messageSplitter = new TrasnferSplitter();
		}

		if (connectModel == ConnectModel.CLIENT) {
			try {
				// 捕获 connect 事件
				catchConnected();
			}catch (IOException e){
				EventTrigger.fireException(session,e);
				Logger.error(e);
				return;
			}
		}

		if(isConnected()) {
			//捕获输入事件
			readByteBuffer = ByteBuffer.allocateDirect(this.getBufferSize());
			catchRead(readByteBuffer);

			//触发 connect 事件
			EventTrigger.fireConnectThread(session);
		}
	}

	/**
	 * 获取 SocketChannel 对象
	 * 
	 * @return 异步 Socket 通道
	 */
	public AsynchronousSocketChannel socketChannel() {
		return this.socketChannel;
	}

	@Override
	public boolean isOpen() {
		if(socketChannel!=null) {
			return socketChannel.isOpen();
		}else{
			return false;
		}
	}

	@Override
	public boolean isConnected() {
		try {
			if (socketChannel.getRemoteAddress() != null) {
				return true;
			} else {
				return false;
			}
		}catch(Exception e){
			return false;
		}
	}

	/**
	 * 同步读取消息
	 * @return 读取出的对象
	 * @throws ReadMessageException  读取消息异常
	 */
	public Object synchronouRead() throws ReadMessageException {
		return session.syncRead();
	}

	/**
	 * 同步发送消息
	 * @param obj  要发送的对象
	 * @throws SendMessageException  消息发送异常
	 */
	public void synchronouSend(Object obj) throws SendMessageException {
		session.syncSend(obj);
	}

	@Override
	public boolean close() {
		if (socketChannel != null) {
			 try {
				// 关闭 Socket 连接
				if (isConnected()) {
					// 触发 DisConnect 事件
					EventTrigger.fireDisconnect(session);
					socketChannel.close();

					readCompletionHandler.release();
					session.getByteBufferChannel().release();
					TByteBuffer.release(readByteBuffer);
					if(session.getSSLParser()!=null){
						session.getSSLParser().release();
					}
				}

				return true;
			} catch (IOException e) {
				Logger.error("SocketChannel close failed",e);
				return false;
			}
		} else {
			return true;
		}
	}

}
