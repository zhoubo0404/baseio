package com.gifisan.nio.jms.server;


import com.gifisan.nio.common.Logger;
import com.gifisan.nio.common.LoggerFactory;
import com.gifisan.nio.jms.Message;

public class MessageWriterJob implements Runnable {

	private static Logger		logger	= LoggerFactory.getLogger(MessageWriterJob.class);
	private Consumer			consumer	= null;
	private Message			message	= null;
	private MQContext			context	= null;

	public MessageWriterJob(MQContext context, Consumer consumer,  Message message) {
		this.context = context;
		this.consumer = consumer;
		this.message = message;
	}

	public void run() {
		try {
			
			consumer.push(message);
			
			context.consumerMessage(message);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			// 回炉
			context.offerMessage(message);

		}

	}

}